package me.example.stash;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Base64;
import java.util.UUID;

public class StashPlugin extends JavaPlugin implements Listener {

    private File dataDir;

    /** Метка, по которой отличаем наш склад от остальных инвентарей. */
    static class StashHolder implements InventoryHolder {
        final UUID owner;
        Inventory inv;

        StashHolder(UUID owner) {
            this.owner = owner;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataDir = new File(getDataFolder(), "data");
        dataDir.mkdirs();
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        // Сохраняем открытые склады при остановке сервера
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof StashHolder h) {
                save(h.owner, h.inv);
            }
        }
    }

    private Component text(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s == null ? "" : s);
    }

    private int size() {
        int s = getConfig().getInt("size", 54);
        if (s < 9 || s > 54 || s % 9 != 0) return 54;
        return s;
    }

    // ---------- проверка расположения ----------

    private boolean nearPoint(Player p) {
        ConfigurationSection sec = getConfig().getConfigurationSection("points");
        if (sec == null) return false;
        Location loc = p.getLocation();
        for (String key : sec.getKeys(false)) {
            ConfigurationSection pt = sec.getConfigurationSection(key);
            if (pt == null) continue;
            World w = Bukkit.getWorld(pt.getString("world", ""));
            if (w == null || !w.equals(loc.getWorld())) continue;
            double r = pt.getDouble("radius", 6);
            Location c = new Location(w, pt.getDouble("x"), pt.getDouble("y"), pt.getDouble("z"));
            if (c.distanceSquared(loc) <= r * r) return true;
        }
        return false;
    }

    // ---------- открытие и закрытие ----------

    private void openStash(Player p) {
        StashHolder holder = new StashHolder(p.getUniqueId());
        holder.inv = Bukkit.createInventory(holder, size(), text(getConfig().getString("title", "&8Тайник")));
        holder.inv.setContents(load(p.getUniqueId()));
        p.openInventory(holder.inv);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof StashHolder h) {
            save(h.owner, e.getInventory());
            if (e.getPlayer() instanceof Player p) {
                p.playSound(p.getLocation(), Sound.BLOCK_IRON_DOOR_CLOSE, 1f, 1f);
                String msg = getConfig().getString("messages.closed", "");
                if (!msg.isEmpty()) p.sendMessage(text(msg));
            }
        }
    }

    @EventHandler
    public void onEnderChest(PlayerInteractEvent e) {
        if (!getConfig().getBoolean("block-vanilla-enderchest", false)) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getClickedBlock() != null && e.getClickedBlock().getType() == Material.ENDER_CHEST) {
            e.setCancelled(true);
        }
    }

    // ---------- хранение ----------

    private File fileOf(UUID id) {
        return new File(dataDir, id + ".yml");
    }

    private void save(UUID id, Inventory inv) {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("items", Base64.getEncoder().encodeToString(
                ItemStack.serializeItemsAsBytes(inv.getContents())));
        try {
            yml.save(fileOf(id));
        } catch (IOException ex) {
            getLogger().severe("Не удалось сохранить склад " + id + ": " + ex.getMessage());
        }
    }

    private ItemStack[] load(UUID id) {
        int size = size();
        ItemStack[] result = new ItemStack[size];
        File f = fileOf(id);
        if (!f.exists()) return result;
        String s = YamlConfiguration.loadConfiguration(f).getString("items");
        if (s == null) return result;
        ItemStack[] items = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(s));
        System.arraycopy(items, 0, result, 0, Math.min(items.length, size));
        return result;
    }

    // ---------- команды ----------
    // /stash open <игрок>       только из консоли (вызывает DeluxeMenus)
    // /stash setpoint [радиус]  добавить точку там, где стоишь
    // /stash points             список точек
    // /stash removepoint <id>   удалить точку
    // /stash clearpoints        удалить все точки
    // /stash reload             перезагрузить конфиг

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 0) {
            if (s.hasPermission("stash.admin")) {
                s.sendMessage("§7/stash setpoint [радиус] | points | removepoint <id> | clearpoints | reload");
            }
            return true;
        }

        // Открытие склада: только из консоли
        if (a[0].equalsIgnoreCase("open")) {
            if (!(s instanceof ConsoleCommandSender) || a.length < 2) return true;
            Player p = Bukkit.getPlayerExact(a[1]);
            if (p == null) return true;
            if (!nearPoint(p)) {
                String msg = getConfig().getString("messages.too-far", "");
                if (!msg.isEmpty()) p.sendMessage(text(msg));
                return true;
            }
            Bukkit.getScheduler().runTask(this, () -> openStash(p));
            return true;
        }

        if (!s.hasPermission("stash.admin")) return true;

        switch (a[0].toLowerCase()) {
            case "setpoint" -> {
                if (!(s instanceof Player p)) {
                    s.sendMessage("§cТолько для игрока.");
                    return true;
                }
                double radius = 6;
                if (a.length > 1) {
                    try {
                        radius = Double.parseDouble(a[1]);
                    } catch (NumberFormatException ignored) {
                    }
                }
                String id = "p" + System.currentTimeMillis();
                Location loc = p.getLocation();
                getConfig().set("points." + id + ".world", loc.getWorld().getName());
                getConfig().set("points." + id + ".x", loc.getX());
                getConfig().set("points." + id + ".y", loc.getY());
                getConfig().set("points." + id + ".z", loc.getZ());
                getConfig().set("points." + id + ".radius", radius);
                saveConfig();
                p.sendMessage("§aТочка " + id + " добавлена, радиус " + radius);
            }
            case "points" -> {
                ConfigurationSection sec = getConfig().getConfigurationSection("points");
                if (sec == null || sec.getKeys(false).isEmpty()) {
                    s.sendMessage("§7Точек нет.");
                    return true;
                }
                for (String key : sec.getKeys(false)) {
                    s.sendMessage("§7" + key + ": §f" + sec.getString(key + ".world") + " "
                            + (int) sec.getDouble(key + ".x") + " "
                            + (int) sec.getDouble(key + ".y") + " "
                            + (int) sec.getDouble(key + ".z")
                            + " r=" + sec.getDouble(key + ".radius"));
                }
            }
            case "removepoint" -> {
                if (a.length < 2 || getConfig().get("points." + a[1]) == null) {
                    s.sendMessage("§cТочка не найдена. Список: /stash points");
                    return true;
                }
                getConfig().set("points." + a[1], null);
                saveConfig();
                s.sendMessage("§aТочка удалена.");
            }
            case "clearpoints" -> {
                getConfig().set("points", null);
                saveConfig();
                s.sendMessage("§aВсе точки удалены.");
            }
            case "reload" -> {
                reloadConfig();
                s.sendMessage("§aКонфиг перезагружен.");
            }
            default -> s.sendMessage("§cНеизвестная подкоманда.");
        }
        return true;
    }
}
