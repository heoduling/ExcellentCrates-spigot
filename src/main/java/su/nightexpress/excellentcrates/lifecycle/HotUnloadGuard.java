package su.nightexpress.excellentcrates.lifecycle;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.RemoteServerCommandEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.Plugin;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.api.opening.Opening;
import su.nightexpress.nightcore.event.MenuOpenEvent;
import su.nightexpress.nightcore.NightPlugin;
import su.nightexpress.nightcore.ui.menu.MenuRegistry;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Closes EC sessions on their owners before a single-target PlugMan operation disables listeners. */
public final class HotUnloadGuard implements Listener {

    private static final Set<String> OPERATIONS = Set.of("unload", "reload", "disable", "restart");
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final int REPLAY_PENDING = 0;
    private static final int REPLAY_STARTED = 1;
    private static final int REPLAY_FINISHED = 2;
    private static final int REPLAY_CANCELED = 3;

    private final CratesPlugin plugin;
    private volatile State state = State.RUNNING;
    private int admittedOpenings;

    private enum State { RUNNING, DRAINING, REPLAY_QUEUED, FAILED, STOPPED }

    private record Replay(Runnable command, Runnable retired, AtomicInteger progress) { }

    public HotUnloadGuard(CratesPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void setup() {
        this.state = State.RUNNING;
        this.admittedOpenings = 0;
        this.plugin.getPluginManager().registerEvents(this, this.plugin);
    }

    public synchronized boolean beginOpening() {
        if (this.state != State.RUNNING || !this.plugin.isEnabled()) return false;
        this.admittedOpenings++;
        return true;
    }

    public synchronized void endOpening() {
        this.admittedOpenings--;
    }

    public boolean isDraining() {
        return this.state != State.RUNNING;
    }

    public void shutdown() {
        this.state = State.STOPPED;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsoleCommand(ServerCommandEvent event) {
        if (this.intercept(event.getSender(), event.getCommand())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRemoteCommand(RemoteServerCommandEvent event) {
        if (this.intercept(event.getSender(), event.getCommand())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (this.intercept(event.getPlayer(), event.getMessage().substring(1))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMenuOpen(MenuOpenEvent event) {
        if (this.isDraining() && this.ownsMenu(event.getMenu())) event.setCancelled(true);
    }

    private boolean intercept(CommandSender sender, String input) {
        String[] args = input.trim().split("\\s+");
        if (args.length != 3 || !OPERATIONS.contains(args[1].toLowerCase(Locale.ROOT))) return false;
        if (!args[2].equalsIgnoreCase(this.plugin.getName())) return false;

        Command command = Bukkit.getCommandMap().getCommand(args[0]);
        if (!(command instanceof PluginIdentifiableCommand identified)) return false;
        Plugin manager = identified.getPlugin();
        if (manager != this.plugin.getPluginManager().getPlugin("PlugManX")) return false;
        String managerLabel = command.getName();
        managerLabel = managerLabel.substring(managerLabel.lastIndexOf(':') + 1).toLowerCase(Locale.ROOT);
        if (!Set.of("plugman", "plm").contains(managerLabel)) return false;
        if (sender instanceof Player && (!command.testPermissionSilent(sender)
            || !sender.hasPermission("plugman." + args[1].toLowerCase(Locale.ROOT)))) return false;

        if (!this.beginPreparation(sender)) return true;

        try {
            Replay replay = createReplay(sender, input);
            send(sender, "正在安全关闭抽奖窗口，完成后自动继续原操作。");
            this.prepare(sender, manager, replay);
        }
        catch (RuntimeException | ReflectiveOperationException exception) {
            this.fail(sender, manager, exception);
        }
        return true;
    }

    public void prepareReload(CommandSender sender) {
        if (!this.beginPreparation(sender)) return;
        Plugin manager = this.plugin.getPluginManager().getPlugin("nightcore");
        try {
            MethodHandle reload = MethodHandles.publicLookup().findVirtual(NightPlugin.class, "doReload",
                MethodType.methodType(void.class, CommandSender.class)).bindTo(this.plugin);
            reload = MethodHandles.insertArguments(reload, 0, sender);
            this.prepare(sender, manager, createReplay(reload));
        }
        catch (RuntimeException | ReflectiveOperationException exception) {
            this.fail(sender, manager, exception);
        }
    }

    private synchronized boolean beginPreparation(CommandSender sender) {
        if (this.state != State.RUNNING) {
            send(sender, this.state == State.FAILED
                ? "抽奖窗口清理未完成，请正常重启服务器。"
                : "正在清理抽奖窗口，请稍候。");
            return false;
        }
        this.state = State.DRAINING;
        return true;
    }

    private void prepare(CommandSender sender, Plugin manager, Replay replay) {
        long deadline = System.nanoTime() + TIMEOUT_NANOS;
        long[] replayDeadline = {0L};
        List<CompletableFuture<Void>> cleanups = new ArrayList<>();
        boolean[] started = {false};

        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this.plugin, task -> {
            if (this.state == State.REPLAY_QUEUED && replay.progress().get() == REPLAY_FINISHED) {
                // Permission changes, manager refusals and sender retirement leave EC enabled.
                this.state = State.RUNNING;
                task.cancel();
                return;
            }
            if (this.state == State.REPLAY_QUEUED) {
                if (System.nanoTime() >= replayDeadline[0]) {
                    task.cancel();
                    if (replay.progress().compareAndSet(REPLAY_PENDING, REPLAY_CANCELED)
                        || replay.progress().get() == REPLAY_FINISHED) {
                        this.state = State.RUNNING;
                        this.plugin.warn("Hot unload command expired before execution; the queued request was canceled.");
                    }
                    else {
                        this.state = State.FAILED;
                        this.plugin.error("Hot unload command is still executing; new openings remain paused.");
                    }
                }
                return;
            }
            if (this.state != State.DRAINING) {
                task.cancel();
                return;
            }
            if (System.nanoTime() >= deadline) {
                task.cancel();
                this.fail(sender, manager, new IllegalStateException("Timed out waiting for opening/window cleanup"));
                return;
            }
            try {
                if (!started[0]) {
                    synchronized (this) {
                        if (this.admittedOpenings != 0) return;
                    }
                    started[0] = true;
                    for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
                        cleanups.add(this.preparePlayer(player));
                    }
                }
                if (cleanups.stream().anyMatch(future -> !future.isDone())) return;
                for (CompletableFuture<Void> cleanup : cleanups) cleanup.getNow(null);
                if (!this.plugin.getOpeningManager().getOpenings().isEmpty()) {
                    throw new IllegalStateException("An opening remains after owner cleanup");
                }

                this.state = State.REPLAY_QUEUED;
                replayDeadline[0] = System.nanoTime() + TIMEOUT_NANOS;
                // The replay itself has only JDK/Bukkit/manager frames, never a retired EC frame.
                if (sender instanceof Player player) {
                    if (!player.getScheduler().execute(manager, replay.command(), replay.retired(), 1L)) {
                        replay.progress().set(REPLAY_FINISHED);
                    }
                }
                else {
                    Bukkit.getGlobalRegionScheduler().execute(manager, replay.command());
                }
            }
            catch (RuntimeException exception) {
                task.cancel();
                this.fail(sender, manager, exception);
            }
        }, 1L, 1L);
    }

    private CompletableFuture<Void> preparePlayer(Player player) {
        CompletableFuture<Void> finished = new CompletableFuture<>();
        UUID playerId = player.getUniqueId();
        Runnable retired = () -> {
            if (this.plugin.getOpeningManager().getOpeningByPlayerIdMap().containsKey(playerId)) {
                finished.completeExceptionally(new IllegalStateException("Player retired with an active opening"));
            }
            else {
                finished.complete(null);
            }
        };
        try {
            ScheduledTask scheduled = player.getScheduler().run(this.plugin, task -> {
                try {
                    if (this.state != State.DRAINING) {
                        throw new IllegalStateException("Cleanup request expired");
                    }
                    this.closePlayerSessions(player);
                    finished.complete(null);
                }
                catch (RuntimeException exception) {
                    finished.completeExceptionally(exception);
                }
            }, retired);
            if (scheduled == null) retired.run();
        }
        catch (RuntimeException exception) {
            finished.completeExceptionally(exception);
        }
        return finished;
    }

    private void closePlayerSessions(Player player) {
        if (!Bukkit.isOwnedByCurrentRegion(player)) {
            throw new IllegalStateException("Opening cleanup must run on the player owner");
        }
        Opening opening = this.plugin.getOpeningManager().getOpening(player);
        if (opening != null) opening.stop();

        var viewer = MenuRegistry.getViewer(player);
        if (viewer != null && this.ownsMenu(viewer.getMenu())) {
            if (player.getOpenInventory() == viewer.getView()) viewer.getMenu().close(player);
            else MenuRegistry.terminate(player);
        }
        var legacy = su.nightexpress.nightcore.menu.impl.AbstractMenu.getMenu(player);
        if (legacy != null && this.ownsMenu(legacy)) legacy.close(player);

        if (this.plugin.getOpeningManager().isOpening(player)) {
            throw new IllegalStateException("Opening did not finish its stop callback");
        }
    }

    private boolean ownsMenu(Object menu) {
        return menu.getClass().getClassLoader() == this.plugin.getClass().getClassLoader();
    }

    private void fail(CommandSender sender, Plugin manager, Exception exception) {
        this.state = State.FAILED;
        this.plugin.error("Hot unload refused: " + exception);
        if (sender instanceof Player player) {
            try {
                player.getScheduler().execute(manager,
                    () -> send(sender, "抽奖窗口清理失败，已中止热卸载，请正常重启服务器。"), null, 1L);
            }
            catch (RuntimeException ignored) {
                // The diagnostic is already in the server log if the sender has left.
            }
        }
        else {
            send(sender, "抽奖窗口清理失败，已中止热卸载，请正常重启服务器。");
        }
    }

    private static Replay createReplay(CommandSender sender, String command) throws ReflectiveOperationException {
        MethodHandle dispatch = MethodHandles.publicLookup().findStatic(Bukkit.class, "dispatchCommand",
            MethodType.methodType(boolean.class, CommandSender.class, String.class));
        MethodHandle bound = MethodHandles.insertArguments(dispatch, 0, sender, command)
            .asType(MethodType.methodType(void.class));
        return createReplay(bound);
    }

    private static Replay createReplay(MethodHandle action) throws ReflectiveOperationException {
        AtomicInteger progress = new AtomicInteger(REPLAY_PENDING);
        MethodHandle claim = MethodHandles.publicLookup().findVirtual(AtomicInteger.class, "compareAndSet",
            MethodType.methodType(boolean.class, int.class, int.class)).bindTo(progress);
        claim = MethodHandles.insertArguments(claim, 0, REPLAY_PENDING, REPLAY_STARTED);
        MethodHandle bound = MethodHandles.guardWithTest(claim, action, MethodHandles.empty(MethodType.methodType(void.class)));
        MethodHandle finish = MethodHandles.publicLookup().findVirtual(AtomicInteger.class, "set",
            MethodType.methodType(void.class, int.class)).bindTo(progress);
        finish = MethodHandles.insertArguments(finish, 0, REPLAY_FINISHED);
        MethodHandle finallyFinish = MethodHandles.dropArguments(finish, 0, Throwable.class);
        Runnable execute = MethodHandleProxies.asInterfaceInstance(Runnable.class, MethodHandles.tryFinally(bound, finallyFinish));
        Runnable retired = MethodHandleProxies.asInterfaceInstance(Runnable.class, finish);
        return new Replay(execute, retired, progress);
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message).decoration(TextDecoration.ITALIC, false));
    }
}
