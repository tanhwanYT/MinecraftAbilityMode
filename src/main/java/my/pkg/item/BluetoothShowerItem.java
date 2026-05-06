package my.pkg.item;

import my.pkg.SupplyItem;
import org.bukkit.*;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

public class BluetoothShowerItem implements SupplyItem {

    private final NamespacedKey itemIdKey;
    private final Map<UUID, BukkitTask> activeTasks = new HashMap<>();

    private static final double RANGE = 8.0;
    private static final double KNOCKBACK = 0.65;
    private static final long FIRE_INTERVAL = 2L;

    public BluetoothShowerItem(NamespacedKey itemIdKey) {
        this.itemIdKey = itemIdKey;
    }

    @Override
    public String id() {
        return "bluetooth_shower";
    }

    @Override
    public ItemStack create(JavaPlugin plugin) {
        ItemStack item = new ItemStack(Material.SHEARS, 1);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName("§b블루투스 샤워기");
            meta.setLore(List.of(
                    "§7우클릭으로 ON / OFF",
                    "§7켜져 있는 동안 물줄기를 발사합니다.",
                    "§7물줄기에 맞은 상대를 밀쳐냅니다.",
                    "§c내구도를 모두 쓰면 깨집니다."
            ));
            meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, id());
            item.setItemMeta(meta);
        }

        return item;
    }

    @Override
    public void onRightClick(JavaPlugin plugin, Player p, PlayerInteractEvent e) {
        e.setCancelled(true);

        UUID uuid = p.getUniqueId();

        if (activeTasks.containsKey(uuid)) {
            stop(p);
            p.sendMessage("§7[블루투스 샤워기] §cOFF");
            p.playSound(p.getLocation(), Sound.BLOCK_LEVER_CLICK, 1.0f, 0.7f);
            return;
        }

        p.sendMessage("§7[블루투스 샤워기] §bON");
        p.playSound(p.getLocation(), Sound.BLOCK_LEVER_CLICK, 1.0f, 1.5f);

        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!p.isOnline() || p.isDead()) {
                    stop(p);
                    cancel();
                    return;
                }

                ItemStack hand = p.getInventory().getItemInMainHand();

                if (!isThisItem(hand)) {
                    stop(p);
                    cancel();
                    return;
                }

                shootWater(plugin, p);
                consumeDurability(p, hand);
            }
        }.runTaskTimer(plugin, 0L, FIRE_INTERVAL);

        activeTasks.put(uuid, task);
    }

    private void shootWater(JavaPlugin plugin, Player p) {
        World world = p.getWorld();
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();

        Set<UUID> hitThisShot = new HashSet<>();

        for (double d = 0.5; d <= RANGE; d += 0.5) {
            Location point = eye.clone().add(dir.clone().multiply(d));

            world.spawnParticle(
                    Particle.SPLASH,
                    point,
                    5,
                    0.12, 0.12, 0.12,
                    0.04
            );

            world.spawnParticle(
                    Particle.BUBBLE,
                    point,
                    2,
                    0.08, 0.08, 0.08,
                    0.02
            );

            if (point.getBlock().getType().isSolid()) {
                world.spawnParticle(Particle.CLOUD, point, 8, 0.2, 0.2, 0.2, 0.02);
                break;
            }

            for (LivingEntity entity : world.getNearbyLivingEntities(point, 0.75, 0.75, 0.75)) {
                if (entity.equals(p)) continue;
                if (hitThisShot.contains(entity.getUniqueId())) continue;

                hitThisShot.add(entity.getUniqueId());

                Vector knock = dir.clone().multiply(KNOCKBACK);
                knock.setY(0.18);

                entity.setVelocity(entity.getVelocity().add(knock));
                entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_PLAYER_SPLASH, 0.6f, 1.4f);
            }
        }

        world.playSound(p.getLocation(), Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, 0.35f, 1.8f);
    }

    private void consumeDurability(Player p, ItemStack item) {
        ItemMeta rawMeta = item.getItemMeta();
        if (!(rawMeta instanceof Damageable meta)) return;

        int max = item.getType().getMaxDurability();
        int nextDamage = meta.getDamage() + 1;

        if (nextDamage >= max) {
            p.getInventory().setItemInMainHand(null);
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
            p.sendMessage("§c블루투스 샤워기가 고장났습니다!");
            stop(p);
            return;
        }

        meta.setDamage(nextDamage);
        item.setItemMeta(meta);
    }

    private boolean isThisItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;

        String id = item.getItemMeta()
                .getPersistentDataContainer()
                .get(itemIdKey, PersistentDataType.STRING);

        return id().equals(id);
    }

    private void stop(Player p) {
        BukkitTask task = activeTasks.remove(p.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }
}