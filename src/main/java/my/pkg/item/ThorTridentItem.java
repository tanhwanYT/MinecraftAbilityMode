package my.pkg.item;

import my.pkg.SupplyItem;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.List;

public class ThorTridentItem implements SupplyItem {

    private final NamespacedKey itemIdKey;
    private final NamespacedKey projectileKey;

    private static final double LIGHTNING_DAMAGE = 4.0; // 2칸
    private static final double DAMAGE_RADIUS = 2.5;

    public ThorTridentItem(NamespacedKey itemIdKey, NamespacedKey projectileKey) {
        this.itemIdKey = itemIdKey;
        this.projectileKey = projectileKey;
    }

    @Override
    public String id() {
        return "thor_trident";
    }

    @Override
    public ItemStack create(JavaPlugin plugin) {
        ItemStack item = new ItemStack(Material.TRIDENT, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§b토르의 삼지창");
            meta.setLore(List.of(
                    "§7던지면 착지 지점에",
                    "§7약한 번개가 내리칩니다.",
                    "§c1회용"
            ));
            meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, id());
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    @Override
    public void onRightClick(JavaPlugin plugin, Player player, PlayerInteractEvent event) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!isThorTrident(hand)) return;

        event.setCancelled(true);

        // 커스텀 삼지창 발사
        Vector dir = player.getLocation().getDirection().normalize();

        Trident trident = player.launchProjectile(Trident.class);
        trident.setVelocity(dir.multiply(2.2));
        trident.setShooter(player);
        trident.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
        trident.getPersistentDataContainer().set(projectileKey, PersistentDataType.BYTE, (byte) 1);

        player.getWorld().playSound(player.getLocation(), Sound.ITEM_TRIDENT_THROW, 1.0f, 1.0f);

        // 1회용 소비
        consumeOneFromMainHand(player);
    }

    @Override
    public void onProjectileHit(JavaPlugin plugin, ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Trident trident)) return;

        Byte mark = trident.getPersistentDataContainer().get(projectileKey, PersistentDataType.BYTE);
        if (mark == null || mark != (byte) 1) return;

        Location hitLoc;
        if (event.getHitBlock() != null) {
            hitLoc = event.getHitBlock().getLocation().add(0.5, 0.5, 0.5);
        } else if (event.getHitEntity() != null) {
            hitLoc = event.getHitEntity().getLocation();
        } else {
            hitLoc = trident.getLocation();
        }

        World w = hitLoc.getWorld();
        if (w == null) return;

        // 번개 연출
        w.strikeLightningEffect(hitLoc);
        w.playSound(hitLoc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.7f, 1.2f);

        Entity shooter = (Entity) trident.getShooter();

        // 약한 번개 피해
        for (Entity e : w.getNearbyEntities(hitLoc, DAMAGE_RADIUS, DAMAGE_RADIUS, DAMAGE_RADIUS)) {
            if (!(e instanceof LivingEntity target)) continue;
            if (shooter != null && e.equals(shooter)) continue;

            if (shooter instanceof Player p) {
                target.damage(LIGHTNING_DAMAGE, p);
            } else {
                target.damage(LIGHTNING_DAMAGE);
            }
        }

        // 삼지창 제거
        trident.remove();
    }

    private boolean isThorTrident(ItemStack item) {
        if (item == null || item.getType() != Material.TRIDENT || !item.hasItemMeta()) return false;
        String value = item.getItemMeta().getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING);
        return value != null && value.equals(id());
    }

    private void consumeOneFromMainHand(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) return;

        int amount = hand.getAmount();
        if (amount <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            hand.setAmount(amount - 1);
            player.getInventory().setItemInMainHand(hand);
        }
    }
}