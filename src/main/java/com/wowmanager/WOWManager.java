
package com.wowmanager;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.*;

public final class WOWManager extends JavaPlugin {

    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();
    private final Set<UUID> disabledScoreboards = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        for (Player player : Bukkit.getOnlinePlayers()) {
            applyRankPermissions(player);
        }

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                updateScoreboard(player);
            }
        }, 20L, 20L);

        getLogger().info("WOWManager enabled successfully!");
    }

    @Override
    public void onDisable() {
        for (PermissionAttachment attachment : attachments.values()) {
            attachment.remove();
        }
        attachments.clear();
        getLogger().info("WOWManager disabled.");
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private String message(String key) {
        return color(getConfig().getString("messages.prefix", "") +
                getConfig().getString("messages." + key, ""));
    }

    private boolean isAdmin(CommandSender sender) {
        if (!sender.hasPermission("wowmanager.admin")) {
            sender.sendMessage(message("no-permission"));
            return false;
        }
        return true;
    }

    private String getRank(Player player) {
        return getConfig().getString(
                "players." + player.getUniqueId(),
                getConfig().getString("default-rank", "PLAYER")
        );
    }

    private String getRankPrefix(Player player) {
        return color(getConfig().getString(
                "ranks." + getRank(player) + ".prefix", ""
        ));
    }

    private void applyRankPermissions(Player player) {
        PermissionAttachment old = attachments.remove(player.getUniqueId());

        if (old != null) {
            old.remove();
        }

        PermissionAttachment attachment = player.addAttachment(this);
        List<String> permissions = getConfig().getStringList(
                "ranks." + getRank(player) + ".permissions"
        );

        for (String permission : permissions) {
            attachment.setPermission(permission, true);
        }

        attachments.put(player.getUniqueId(), attachment);
        player.recalculatePermissions();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {

        String cmd = command.getName().toLowerCase();

        switch (cmd) {

            case "announcement":
                if (!isAdmin(sender)) return true;

                if (args.length == 0) {
                    sender.sendMessage(color("&cUsage: /announcement <message>"));
                    return true;
                }

                String announcement = String.join(" ", args);
                String format = getConfig().getString(
                        "announcement-format",
                        "&6Announcement: &f{message}"
                );

                Bukkit.broadcastMessage(color(
                        format.replace("{message}", announcement)
                ));
                return true;

            case "setspawn":
                if (!isAdmin(sender)) return true;

                if (!(sender instanceof Player player)) {
                    sender.sendMessage(message("player-only"));
                    return true;
                }

                Location location = player.getLocation();

                getConfig().set("spawn.world", location.getWorld().getName());
                getConfig().set("spawn.x", location.getX());
                getConfig().set("spawn.y", location.getY());
                getConfig().set("spawn.z", location.getZ());
                getConfig().set("spawn.yaw", location.getYaw());
                getConfig().set("spawn.pitch", location.getPitch());
                saveConfig();

                player.sendMessage(message("spawn-set"));
                return true;

            case "spawn":
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(message("player-only"));
                    return true;
                }

                if (getConfig().getString("spawn.world") == null) {
                    player.sendMessage(color("&cSpawn has not been set yet."));
                    return true;
                }

                org.bukkit.World world = Bukkit.getWorld(
                        getConfig().getString("spawn.world")
                );

                if (world == null) {
                    player.sendMessage(color("&cSpawn world is unavailable."));
                    return true;
                }

                Location spawn = new Location(
                        world,
                        getConfig().getDouble("spawn.x"),
                        getConfig().getDouble("spawn.y"),
                        getConfig().getDouble("spawn.z"),
                        (float) getConfig().getDouble("spawn.yaw"),
                        (float) getConfig().getDouble("spawn.pitch")
                );

                player.teleport(spawn);
                player.sendMessage(message("teleporting"));
                return true;

            case "rank":
                if (!isAdmin(sender)) return true;
                return handleRank(sender, args);

            case "scoreboard":
                if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                    if (!isAdmin(sender)) return true;

                    reloadConfig();

                    for (Player player : Bukkit.getOnlinePlayers()) {
                        updateScoreboard(player);
                    }

                    sender.sendMessage(color("&aWOWManager reloaded!"));
                    return true;
                }

                if (!(sender instanceof Player player)) {
                    sender.sendMessage(message("player-only"));
                    return true;
                }

                if (disabledScoreboards.contains(player.getUniqueId())) {
                    disabledScoreboards.remove(player.getUniqueId());
                    player.sendMessage(color("&aScoreboard enabled."));
                    updateScoreboard(player);
                } else {
                    disabledScoreboards.add(player.getUniqueId());
                    player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
                    player.sendMessage(color("&cScoreboard disabled."));
                }
                return true;
        }

        return false;
    }

    private boolean handleRank(CommandSender sender, String[] args) {

        if (args.length == 0) {
            sender.sendMessage(color("&e/rank create <name>"));
            sender.sendMessage(color("&e/rank delete <name>"));
            sender.sendMessage(color("&e/rank set <player> <rank>"));
            sender.sendMessage(color("&e/rank remove <player>"));
            sender.sendMessage(color("&e/rank list"));
            return true;
        }

        String action = args[0].toLowerCase();

        switch (action) {

            case "create":
                if (args.length < 2) return false;

                String createName = args[1].toUpperCase();

                if (getConfig().contains("ranks." + createName)) {
                    sender.sendMessage(color("&cRank already exists."));
                    return true;
                }

                getConfig().set("ranks." + createName + ".prefix",
                        "&7[" + createName + "]");
                getConfig().set("ranks." + createName + ".suffix", "");
                getConfig().set("ranks." + createName + ".weight", 0);
                getConfig().set("ranks." + createName + ".permissions",
                        new ArrayList<String>());
                saveConfig();

                sender.sendMessage(color("&aRank " + createName + " created!"));
                return true;

            case "delete":
                if (args.length < 2) return false;

                String deleteName = args[1].toUpperCase();

                if (deleteName.equalsIgnoreCase(
                        getConfig().getString("default-rank", "PLAYER"))) {
                    sender.sendMessage(color("&cYou cannot delete the default rank."));
                    return true;
                }

                if (!getConfig().contains("ranks." + deleteName)) {
                    sender.sendMessage(color("&cRank does not exist."));
                    return true;
                }

                getConfig().set("ranks." + deleteName, null);
                saveConfig();

                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (getRank(player).equalsIgnoreCase(deleteName)) {
                        getConfig().set("players." + player.getUniqueId(),
                                getConfig().getString("default-rank", "PLAYER"));
                        applyRankPermissions(player);
                    }
                }

                saveConfig();
                sender.sendMessage(color("&aRank deleted."));
                return true;

            case "set":
                if (args.length < 3) return false;

                Player target = Bukkit.getPlayerExact(args[1]);
                String rank = args[2].toUpperCase();

                if (target == null) {
                    sender.sendMessage(color("&cPlayer must be online."));
                    return true;
                }

                if (!getConfig().contains("ranks." + rank)) {
                    sender.sendMessage(color("&cRank does not exist."));
                    return true;
                }

                getConfig().set("players." + target.getUniqueId(), rank);
                saveConfig();
                applyRankPermissions(target);
                target.sendMessage(color("&aYour rank is now " + rank + "!"));
                sender.sendMessage(color("&aRank updated for " + target.getName()));
                return true;

            case "remove":
                if (args.length < 2) return false;

                Player removeTarget = Bukkit.getPlayerExact(args[1]);

                if (removeTarget == null) {
                    sender.sendMessage(color("&cPlayer must be online."));
                    return true;
                }

                getConfig().set("players." + removeTarget.getUniqueId(),
                        getConfig().getString("default-rank", "PLAYER"));
                saveConfig();
                applyRankPermissions(removeTarget);

                sender.sendMessage(color("&aRank reset for " + removeTarget.getName()));
                return true;

            case "list":
                ConfigurationSection ranks = getConfig().getConfigurationSection("ranks");

                sender.sendMessage(color("&b&lWOWManager Ranks:"));

                if (ranks != null) {
                    for (String name : ranks.getKeys(false)) {
                        sender.sendMessage(color("&7- &f" + name));
                    }
                }
                return true;

            default:
                sender.sendMessage(color("&cUnknown rank command."));
                return true;
        }
    }

    private void updateScoreboard(Player player) {

        if (!getConfig().getBoolean("scoreboard.enabled", true)
                || disabledScoreboards.contains(player.getUniqueId())) {
            return;
        }

        org.bukkit.scoreboard.ScoreboardManager manager =
                Bukkit.getScoreboardManager();

        if (manager == null) return;

        Scoreboard board = manager.getNewScoreboard();

        Objective objective = board.registerNewObjective(
                "wowmanager", "dummy",
                color(getConfig().getString("scoreboard.title", "&bWOW SMP"))
        );

        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        List<String> lines = getConfig().getStringList("scoreboard.lines");
        int score = lines.size();

        for (String line : lines) {

            String value = line
                    .replace("%player%", player.getName())
                    .replace("%rank%", getRank(player))
                    .replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()))
                    .replace("%prefix%", getRankPrefix(player));

            String entry = ChatColor.values()[score % ChatColor.values().length].toString()
                    + ChatColor.RESET;

            Team team = board.registerNewTeam("line" + score);
            team.addEntry(entry);

            String colored = color(value);

            String prefix = colored.length() > 64
                    ? colored.substring(0, 64) : colored;

            team.setPrefix(prefix);
            objective.getScore(entry).setScore(score);
            score--;
        }

        player.setScoreboard(board);
    }
              }
