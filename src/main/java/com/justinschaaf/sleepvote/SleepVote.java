package com.justinschaaf.sleepvote;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class SleepVote extends JavaPlugin implements Listener {

    /*
     * Variables
     */

    // Config Values
    protected double percent = 0.33;
    protected Set<GameMode> exempt = new HashSet<>(); // This is a set so values can't repeat

    // Localization
    protected String playerEnterBedStr = "    &f&l%p%&7 has started sleeping (&f&l%c%&7/&f&l%t%&7)";
    protected String playerLeaveBedStr = "    &f&l%p%&7 has stopped sleeping (&f&l%c%&7/&f&l%t%&7)";
    protected String becomeDaytimeStr = "    &7Good morning, everyone!";

    // In use
    protected final HashMap<Player, Integer> SLEEPING_EVENTS = new HashMap<>();

    /*
     * Setup
     */

    @Override
    public void onEnable() {

        loadConfig();

        // Register events
        getServer().getPluginManager().registerEvents(this, this);

    }

    @Override
    public void onDisable() {
        SLEEPING_EVENTS.clear();
        exempt.clear();
        Bukkit.getScheduler().cancelTasks(this);
    }

    /**
     * Loads the values this plugin uses from the config
     */
    protected void loadConfig() {

        saveDefaultConfig();

        // Main config values
        percent = getConfig().getDouble("sleep_percent", 0.33);
        List<String> gamemodeStr = getConfig().getStringList("exempt_gamemodes");
        for (String gm : gamemodeStr) exempt.add(GameMode.valueOf(gm.strip().toUpperCase()));

        // Localization
        playerEnterBedStr = getConfig().getString("localization.player_enter_bed", "    &f&l%p%&7 has started sleeping (&f&l%c%&7/&f&l%t%&7)");
        playerLeaveBedStr = getConfig().getString("localization.player_leave_bed", "    &f&l%p%&7 has stopped sleeping (&f&l%c%&7/&f&l%t%&7)");
        becomeDaytimeStr = getConfig().getString("localization.become_daytime", "    &7Good morning, everyone!");

    }

    /*
     * Events
     */

    /**
     * Whenever a player enters a bed, this schedules the task to check the sleep vote
     *
     * @param e The sleep event
     */
    @EventHandler
    public void onBedEnter(PlayerBedEnterEvent e) {

        if (e.getBedEnterResult() == PlayerBedEnterEvent.BedEnterResult.OK) {

            // Cancel any previous tasks
            if (SLEEPING_EVENTS.containsKey(e.getPlayer())) Bukkit.getScheduler().cancelTask(SLEEPING_EVENTS.get(e.getPlayer()));

            SLEEPING_EVENTS.put(e.getPlayer(), Bukkit.getScheduler().scheduleSyncDelayedTask(this, () -> {

                SLEEPING_EVENTS.remove(e.getPlayer());

                int total = getTotalSleepCandidates(e.getPlayer().getWorld());
                int required = getRequiredSleeping(total);
                int current = 0;

                // Get players who are currently sleeping in the world
                for (Player p : e.getPlayer().getWorld().getPlayers()) if (p.isSleeping() && p.getSleepTicks() >= 100) current++;

                // Output message
                String feedback = formatMessage(playerEnterBedStr, "%p%", e.getPlayer().getDisplayName(), "%c%", current + "", "%t%", total + "");
                for (Player p : e.getPlayer().getWorld().getPlayers()) p.sendMessage(feedback);

                // Do check for sleeping
                doSleepCheck(e.getPlayer().getWorld(), current, required);

            }, 100)); // Players sleep in a bed for 101 ticks. Meant to mimic vanilla behavior

        }

    }

    /**
     * Whenever a player leaves a bed, this cancels any previous tasks and informs other players that a person left their bed
     *
     * @param e The sleep event
     */
    @EventHandler
    public void onBedLeave(PlayerBedLeaveEvent e) {

        // Cancel any previous tasks
        if (SLEEPING_EVENTS.containsKey(e.getPlayer())) {
            Bukkit.getScheduler().cancelTask(SLEEPING_EVENTS.get(e.getPlayer()));
            SLEEPING_EVENTS.remove(e.getPlayer());
        }

        // If it's the middle of day and sunny, don't bother sending a message
        if (isSleepConditions(e.getPlayer().getWorld())) {

            int total = getTotalSleepCandidates(e.getPlayer().getWorld());
            int current = 0;

            // Get players who are currently sleeping in the world
            for (Player p : e.getPlayer().getWorld().getPlayers()) if (p.isSleeping() && p.getSleepTicks() >= 100) current++;

            // Send feedback
            String feedback = formatMessage(playerLeaveBedStr, "%p%", e.getPlayer().getDisplayName(), "%c%", current + "", "%t%", total + "");
            for (Player p : e.getPlayer().getWorld().getPlayers()) p.sendMessage(feedback);

        }

    }

    /**
     * Whenever a player changes their gamemode, this checks if their gamemode is now eligible for the sleep count and updates accordingly
     *
     * @param e The gamemode change event
     */
    @EventHandler
    public void onGamemodeChange(PlayerGameModeChangeEvent e) {
        if (!isExempt(e.getPlayer()) && isSleepConditions(e.getPlayer().getWorld())) doSleepCheck(e.getPlayer().getWorld());
    }

    /*
     * Utils
     */

    /**
     * Checks whether or not enough players are sleeping to make it day, and progresses to day if so
     *
     * @param world The world to check
     */
    protected void doSleepCheck(World world) {

        int required = getRequiredSleeping(getTotalSleepCandidates(world));
        int current = 0;

        // Get players who are currently sleeping in the world
        for (Player p : world.getPlayers()) if (p.isSleeping() && p.getSleepTicks() >= 100) current++;

        doSleepCheck(world, current, required);

    }

    /**
     * Checks whether or not enough players are sleeping to make it day, and progresses to day if so
     *
     * @param world The world to check
     * @param current The number of players currently sleeping
     * @param required The number of players required to sleep to skip the night
     */
    protected void doSleepCheck(World world, int current, int required) {

        if (current >= required) {

            // Clear events
            SLEEPING_EVENTS.forEach((p, v) -> {
                Bukkit.getScheduler().cancelTask(v);
            });

            SLEEPING_EVENTS.clear();

            // Make it day and clear weather
            world.setTime(0);
            world.setWeatherDuration(0);

            // Send feedback
            String feedback = formatMessage(becomeDaytimeStr);
            for (Player p : world.getPlayers()) p.sendMessage(feedback);

        }

    }

    /**
     * Checks whether or not it is valid to sleep in the given world
     *
     * @param world The world to check
     * @return true if sleeping conditions are valid
     */
    protected boolean isSleepConditions(World world) {
        return world.isThundering() || (world.getTime() >= 12541 && world.getTime() <= 23458);
    }

    /**
     * Gets the number of players required to be sleeping to make it day
     *
     * @param total The total number of sleep candidates, ideally from {@link #getTotalSleepCandidates(World)}
     * @return The number of players required to be sleeping to make it day with the given total candidates
     */
    protected int getRequiredSleeping(int total) {
        return (int) ((total * percent) + 0.5); // Rounds up
    }

    /**
     * Gets the total number of sleep candidates in the given world.
     * Sleep candidates must not have the exempting permission, be in an exempt gamemode, or in a different world
     *
     * @param world The world to check
     * @return The number of sleep candidates for the given world
     */
    protected int getTotalSleepCandidates(World world) {

        int total = 0;

        for (Player player : world.getPlayers()) if (isExempt(player)) total++;

        return total;

    }

    /**
     * Determines whether or not a player is exempt from the required sleep total
     * Sleep candidates must not have the exempting permission or be in an exempt gamemode
     *
     * @param p The player to check
     * @return Whether or not the player should be exempt from the sleep total
     */
    protected boolean isExempt(Player p) {
        return p.hasPermission("sleepvote.exempt") || exempt.contains(p.getGameMode());
    }

    /**
     * Formats a message with the given replacement variables and color codes
     * Replacements should be given in pairs of two, the variable and the replacement.
     * e.g. ["%p%", "Steve"]
     *
     * @param message The message to format
     * @param replacements The variables and replacements to substitute
     * @return The formatted message
     */
    protected String formatMessage(String message, String... replacements) {

        // Iterate through every other element because they're in pairs
        for (int i = 0; i < replacements.length; i += 2) message = message.replace(replacements[i], replacements[i + 1]);

        // Format chat colors and return
        return ChatColor.translateAlternateColorCodes('&', message);

    }

}
