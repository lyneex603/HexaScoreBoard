package ru.hexatime.scoreboard;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class HexaScoreboard extends JavaPlugin implements Listener {

    private Economy economy;
    private LuckPerms luckPerms;
    private File dataFile;
    private FileConfiguration data;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final DecimalFormat money = new DecimalFormat("#,###.##");

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);

        hookServices();
        getServer().getPluginManager().registerEvents(this, this);

        long interval = Math.max(5L, getConfig().getLong("update-interval", 20L));
        Bukkit.getScheduler().runTaskTimer(this, this::updateAll, 20L, interval);
        Bukkit.getScheduler().runTaskTimer(this, this::saveData, 6000L, 6000L);

        for (Player p : Bukkit.getOnlinePlayers()) {
            createBoard(p);
        }
    }

    @Override
    public void onDisable() {
        saveData();
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
        boards.clear();
    }

    private void hookServices() {
        RegisteredServiceProvider<Economy> eco = getServer().getServicesManager().getRegistration(Economy.class);
        if (eco != null) {
            economy = eco.getProvider();
        } else {
            getLogger().warning("Vault/Economy не найден - баланс будет 0 (нужны Vault + EssentialsX).");
        }
        RegisteredServiceProvider<LuckPerms> lp = getServer().getServicesManager().getRegistration(LuckPerms.class);
        if (lp != null) {
            luckPerms = lp.getProvider();
        } else {
            getLogger().warning("LuckPerms не найден - ранг будет 'Игрок'.");
        }
    }

    // ---------- Скорборд ----------

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private void createBoard(Player p) {
        List<String> lines = getConfig().getStringList("lines");
        int n = Math.min(lines.size(), 15);

        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = sb.registerNewObjective("hexa", "dummy", color(getConfig().getString("title", "HexaTime")));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        for (int i = 0; i < n; i++) {
            String entry = ChatColor.values()[i].toString() + ChatColor.RESET;
            Team team = sb.registerNewTeam("l" + i);
            team.addEntry(entry);
            obj.getScore(entry).setScore(n - i);
        }
        boards.put(p.getUniqueId(), sb);
        p.setScoreboard(sb);
        update(p);
    }

    private void updateAll() {
        if (economy == null || luckPerms == null) {
            hookServices();
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            update(p);
        }
    }

    private void update(Player p) {
        Scoreboard sb = boards.get(p.getUniqueId());
        if (sb == null) {
            createBoard(p);
            return;
        }
        List<String> lines = getConfig().getStringList("lines");
        int n = Math.min(lines.size(), 15);
        for (int i = 0; i < n; i++) {
            Team team = sb.getTeam("l" + i);
            if (team != null) {
                team.setPrefix(color(replace(lines.get(i), p)));
            }
        }
    }

    private String replace(String line, Player p) {
        long hours = p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 72000L
                + getConfig().getLong("playtime-start-hours", 0L);
        double bal = economy != null ? economy.getBalance(p) : 0;
        return line
                .replace("%player%", p.getName())
                .replace("%rank%", rank(p))
                .replace("%balance%", money.format(bal))
                .replace("%hexes%", String.valueOf(getHexes(p.getUniqueId())))
                .replace("%kills%", String.valueOf(data.getInt("players." + p.getUniqueId() + ".kills")))
                .replace("%deaths%", String.valueOf(data.getInt("players." + p.getUniqueId() + ".deaths")))
                .replace("%playtime%", String.valueOf(hours))
                .replace("%ping%", String.valueOf(p.getPing()));
    }

    private String rank(Player p) {
        if (luckPerms == null) {
            return "Игрок";
        }
        User user = luckPerms.getPlayerAdapter(Player.class).getUser(p);
        if ("prefix".equalsIgnoreCase(getConfig().getString("rank-source", "group"))) {
            String prefix = user.getCachedData().getMetaData().getPrefix();
            if (prefix != null && !prefix.isBlank()) {
                return prefix.trim();
            }
        }
        String group = user.getPrimaryGroup();
        String name = getConfig().getString("rank-names." + group);
        if (name != null) {
            return name;
        }
        return group.substring(0, 1).toUpperCase() + group.substring(1);
    }

    // ---------- Статистика (убийства/смерти) ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        createBoard(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        boards.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        addInt("players." + victim.getUniqueId() + ".deaths", 1);
        Player killer = victim.getKiller();
        if (killer != null && !killer.equals(victim)) {
            addInt("players." + killer.getUniqueId() + ".kills", 1);
        }
    }

    // ---------- Гексы ----------

    public int getHexes(UUID id) {
        return data.getInt("players." + id + ".hexes", 0);
    }

    public void setHexes(UUID id, int amount) {
        data.set("players." + id + ".hexes", Math.max(0, amount));
    }

    private void addInt(String path, int delta) {
        data.set(path, data.getInt(path, 0) + delta);
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe("Не удалось сохранить data.yml: " + ex.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("hexes")) {
            return false;
        }

        // /hexes - свой баланс
        if (args.length == 0) {
            if (sender instanceof Player p) {
                sender.sendMessage(color("&aУ вас &d" + getHexes(p.getUniqueId()) + " &aгексов."));
            } else {
                sender.sendMessage("Использование: /hexes <balance|give|take|set> <игрок> [число]");
            }
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("reload") && sender.hasPermission("hexatime.admin")) {
            reloadConfig();
            for (Player p : Bukkit.getOnlinePlayers()) {
                createBoard(p);
            }
            sender.sendMessage(color("&aКонфиг перезагружен."));
            return true;
        }

        if (sub.equals("balance")) {
            OfflinePlayer target = args.length > 1 ? Bukkit.getOfflinePlayer(args[1])
                    : (sender instanceof Player p ? p : null);
            if (target == null) {
                sender.sendMessage("Укажите игрока.");
                return true;
            }
            sender.sendMessage(color("&aГексы " + target.getName() + ": &d" + getHexes(target.getUniqueId())));
            return true;
        }

        if (!sender.hasPermission("hexatime.admin")) {
            sender.sendMessage(color("&cНет прав."));
            return true;
        }

        if ((sub.equals("give") || sub.equals("take") || sub.equals("set")) && args.length >= 3) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
            int amount;
            try {
                amount = Integer.parseInt(args[2]);
            } catch (NumberFormatException ex) {
                sender.sendMessage(color("&cЧисло указано неверно."));
                return true;
            }
            int current = getHexes(target.getUniqueId());
            switch (sub) {
                case "give" -> setHexes(target.getUniqueId(), current + amount);
                case "take" -> setHexes(target.getUniqueId(), current - amount);
                default -> setHexes(target.getUniqueId(), amount);
            }
            sender.sendMessage(color("&aТеперь у " + target.getName() + " &d"
                    + getHexes(target.getUniqueId()) + " &aгексов."));
            return true;
        }

        sender.sendMessage(color("&cИспользование: /hexes <balance|give|take|set|reload> <игрок> [число]"));
        return true;
    }
}
