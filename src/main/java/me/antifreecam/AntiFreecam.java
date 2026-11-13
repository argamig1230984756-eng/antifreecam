package me.antifreecam;

import org.bukkit.Location;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class AntiFreecam extends JavaPlugin implements Listener {

    private final Map<UUID, Set<UUID>> hidden = new HashMap<>();

    private int interval;
    private double maxDist;
    private double alwaysVisible;
    private boolean hidePlayers;
    private boolean hideMobs;
    private boolean blockSpectatorTp;
    private boolean forceOutOfSpectator;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        startTask();
    }

    @Override
    public void onDisable() {
        // Re-show everything we hid
        for (Player p : getServer().getOnlinePlayers()) {
            Set<UUID> set = hidden.get(p.getUniqueId());
            if (set == null) continue;
            for (UUID id : set) {
                Entity e = getServer().getEntity(id);
                if (e != null) p.showEntity(this, e);
            }
        }
        hidden.clear();
    }

    private void loadSettings() {
        reloadConfig();
        interval = Math.max(1, getConfig().getInt("check-interval-ticks", 4));
        maxDist = getConfig().getDouble("max-check-distance", 64);
        alwaysVisible = getConfig().getDouble("always-visible-distance", 6);
        hidePlayers = getConfig().getBoolean("hide-players", true);
        hideMobs = getConfig().getBoolean("hide-mobs", true);
        blockSpectatorTp = getConfig().getBoolean("block-spectator-teleport", true);
        forceOutOfSpectator = getConfig().getBoolean("force-out-of-spectator", false);
    }

    private void startTask() {
        getServer().getScheduler().cancelTasks(this);
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, interval);
    }

    private void tick() {
        for (Player viewer : getServer().getOnlinePlayers()) {
            if (viewer.hasPermission("antifreecam.bypass")) continue;
            if (viewer.getGameMode() == GameMode.SPECTATOR) continue;

            Set<UUID> hiddenSet = hidden.computeIfAbsent(viewer.getUniqueId(), k -> new HashSet<>());
            Location eye = viewer.getEyeLocation();

            for (Entity target : viewer.getNearbyEntities(maxDist, maxDist, maxDist)) {
                if (!(target instanceof LivingEntity)) continue;
                boolean isPlayer = target instanceof Player;
                if (isPlayer && !hidePlayers) continue;
                if (!isPlayer && !hideMobs) continue;

                double dist = target.getLocation().distance(eye);
                boolean visible = dist <= alwaysVisible || canSee(eye, (LivingEntity) target);
                UUID id = target.getUniqueId();

                if (visible && hiddenSet.remove(id)) {
                    viewer.showEntity(this, target);
                } else if (!visible && hiddenSet.add(id)) {
                    viewer.hideEntity(this, target);
                }
            }

            // Clean up entities that no longer exist / are out of range
            hiddenSet.removeIf(id -> {
                Entity e = getServer().getEntity(id);
                if (e == null || !e.isValid()) return true;
                if (!e.getWorld().equals(viewer.getWorld())
                        || e.getLocation().distanceSquared(eye) > maxDist * maxDist) {
                    viewer.showEntity(this, e);
                    return true;
                }
                return false;
            });
        }
    }

    /** Checks several points on the target body for an unobstructed line from the eye. */
    private boolean canSee(Location eye, LivingEntity target) {
        Location base = target.getLocation();
        double h = target.getHeight();
        double w = target.getWidth() / 2.0;
        double[][] points = {
                {0, h * 0.9, 0},
                {0, h * 0.5, 0},
                {0, 0.1, 0},
                {w, h * 0.5, 0},
                {-w, h * 0.5, 0},
                {0, h * 0.5, w},
                {0, h * 0.5, -w}
        };
        for (double[] p : points) {
            Location to = base.clone().add(p[0], p[1], p[2]);
            if (clearLine(eye, to)) return true;
        }
        return false;
    }

    private boolean clearLine(Location from, Location to) {
        World world = from.getWorld();
        Vector dir = to.toVector().subtract(from.toVector());
        double length = dir.length();
        if (length < 0.5) return true;
        dir.normalize();

        double step = 0.5;
        Vector pos = from.toVector();
        for (double d = step; d < length; d += step) {
            pos.copy(from.toVector()).add(dir.clone().multiply(d));
            int x = pos.getBlockX(), y = pos.getBlockY(), z = pos.getBlockZ();
            if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
            Block b = world.getBlockAt(x, y, z);
            if (b.getType().isOccluding()) return false;
        }
        return true;
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        if (!blockSpectatorTp) return;
        if (e.getCause() != PlayerTeleportEvent.TeleportCause.SPECTATE) return;
        if (e.getPlayer().hasPermission("antifreecam.bypass")) return;
        e.setCancelled(true);
    }

    @EventHandler
    public void onGameMode(PlayerGameModeChangeEvent e) {
        if (!forceOutOfSpectator) return;
        if (e.getNewGameMode() != GameMode.SPECTATOR) return;
        if (e.getPlayer().hasPermission("antifreecam.bypass")) return;
        e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        hidden.remove(id);
        for (Set<UUID> set : hidden.values()) set.remove(id);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            loadSettings();
            startTask();
            sender.sendMessage("AntiFreecam config reloaded.");
            return true;
        }
        sender.sendMessage("Usage: /antifreecam reload");
        return true;
    }
}
