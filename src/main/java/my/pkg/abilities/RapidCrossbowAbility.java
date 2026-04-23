package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class RapidCrossbowAbility implements Ability, Listener {

    private final JavaPlugin plugin;
    private final NamespacedKey crossbowKey;

    // 능력 보유자
    private final Set<UUID> holders = new HashSet<>();

    // 현재 액티브 사용 중인 플레이어
    private final Set<UUID> activeUsers = new HashSet<>();

    private static final int COOLDOWN = 5;
    private static final long ACTIVE_TICKS = 20L * 8L; // 8초

    public RapidCrossbowAbility(JavaPlugin plugin) {
        this.plugin = plugin;
        this.crossbowKey = new NamespacedKey(plugin, "rapid_crossbow");
    }

    @Override
    public String id() {
        return "rapid_crossbow";
    }

    @Override
    public String name() {
        return "연사쇠뇌";
    }

    @Override
    public int cooldownSeconds() {
        return COOLDOWN;
    }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        holders.add(player.getUniqueId());
        player.sendMessage("§6[연사쇠뇌] §f네더스타 우클릭 시 8초간 따발쇠뇌를 사용합니다.");
        player.sendMessage("§7쏠 때마다 반동으로 밀려납니다.");
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        UUID uuid = player.getUniqueId();
        holders.remove(uuid);
        activeUsers.remove(uuid);
        removeAllRapidCrossbows(player);
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        UUID uuid = player.getUniqueId();

        if (!holders.contains(uuid)) {
            return false;
        }

        if (activeUsers.contains(uuid)) {
            player.sendMessage("§c[연사쇠뇌] 이미 사용 중입니다.");
            return false;
        }

        activeUsers.add(uuid);
        removeAllRapidCrossbows(player);
        giveLoadedCrossbow(player);

        player.sendMessage("§6[연사쇠뇌] §f8초 동안 난사를 시작합니다!");

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;

                activeUsers.remove(uuid);
                removeAllRapidCrossbows(player);
                player.sendMessage("§7[연사쇠뇌] 난사가 종료되었습니다.");
            }
        }.runTaskLater(plugin, ACTIVE_TICKS);

        return true;
    }

    @EventHandler
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.isCancelled()) return;

        UUID uuid = player.getUniqueId();

        if (!holders.contains(uuid)) return;
        if (!activeUsers.contains(uuid)) return;
        if (event.getBow() == null || !isRapidCrossbow(event.getBow())) return;
        if (!(event.getProjectile() instanceof AbstractArrow mainArrow)) return;

        mainArrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        mainArrow.setPierceLevel(4);

        // 발사 반동
        applyRecoil(player);

        mainArrow.setDamage(mainArrow.getDamage() * 0.5);
        // 좌우 추가 화살 2발 생성
        spawnSideArrow(player, mainArrow, -10.0);
        spawnSideArrow(player, mainArrow, 10.0);

        // 쏜 쇠뇌 제거
        removeOneUsedRapidCrossbow(player);

        // 1틱 뒤 새 장전 쇠뇌 지급
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;
                if (!holders.contains(uuid)) return;
                if (!activeUsers.contains(uuid)) return;

                giveLoadedCrossbow(player);
            }
        }.runTaskLater(plugin, 1L);
    }

    private void applyRecoil(Player player) {
        Vector look = player.getEyeLocation().getDirection().normalize();

        // 보는 방향 반대로 뒤로 밀림
        Vector recoil = look.clone().multiply(-0.6);

        // 아래 보고 쏘면 위로 튀어오르게
        recoil.setY(Math.max(0.12, -look.getY() * 0.6));

        player.setVelocity(player.getVelocity().add(recoil));
    }

    private void spawnSideArrow(Player shooter, AbstractArrow origin, double yawOffsetDegrees) {
        Location loc = origin.getLocation().clone();
        Vector dir = origin.getVelocity().clone().normalize();

        Vector rotated = rotateYaw(dir, yawOffsetDegrees).multiply(origin.getVelocity().length());

        AbstractArrow extra = shooter.getWorld().spawnArrow(
                loc,
                rotated,
                (float) rotated.length(),
                0.0f
        );

        extra.setShooter(shooter);
        extra.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        extra.setPierceLevel(4);
        extra.setDamage(origin.getDamage() * 0.5);
        extra.setCritical(origin.isCritical());
    }

    private Vector rotateYaw(Vector v, double degrees) {
        double rad = Math.toRadians(degrees);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);

        double x = v.getX() * cos - v.getZ() * sin;
        double z = v.getX() * sin + v.getZ() * cos;

        return new Vector(x, v.getY(), z);
    }

    private void giveLoadedCrossbow(Player player) {
        ItemStack crossbow = createLoadedCrossbow();

        ItemStack main = player.getInventory().getItemInMainHand();
        if (main == null || main.getType() == Material.AIR) {
            player.getInventory().setItemInMainHand(crossbow);
            return;
        }

        var leftover = player.getInventory().addItem(crossbow);
        if (!leftover.isEmpty()) {
            leftover.values().forEach(item ->
                    player.getWorld().dropItemNaturally(player.getLocation(), item)
            );
        }
    }

    private ItemStack createLoadedCrossbow() {
        ItemStack item = new ItemStack(Material.CROSSBOW);
        CrossbowMeta meta = (CrossbowMeta) item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName("§c§l연사 쇠뇌");
        meta.addEnchant(Enchantment.PIERCING, 4, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

        meta.getPersistentDataContainer().set(crossbowKey, PersistentDataType.BYTE, (byte) 1);

        // 장전된 상태
        ItemStack arrow = new ItemStack(Material.ARROW);
        meta.addChargedProjectile(arrow);

        item.setItemMeta(meta);
        return item;
    }

    private boolean isRapidCrossbow(ItemStack item) {
        if (item == null || item.getType() != Material.CROSSBOW) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;

        Byte value = meta.getPersistentDataContainer().get(crossbowKey, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    private void removeOneUsedRapidCrossbow(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (isRapidCrossbow(main)) {
            player.getInventory().setItemInMainHand(null);
            return;
        }

        ItemStack off = player.getInventory().getItemInOffHand();
        if (isRapidCrossbow(off)) {
            player.getInventory().setItemInOffHand(null);
            return;
        }

        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (isRapidCrossbow(item)) {
                player.getInventory().setItem(i, null);
                return;
            }
        }
    }

    private void removeAllRapidCrossbows(Player player) {
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (isRapidCrossbow(item)) {
                player.getInventory().setItem(i, null);
            }
        }

        ItemStack off = player.getInventory().getItemInOffHand();
        if (isRapidCrossbow(off)) {
            player.getInventory().setItemInOffHand(null);
        }

        ItemStack main = player.getInventory().getItemInMainHand();
        if (isRapidCrossbow(main)) {
            player.getInventory().setItemInMainHand(null);
        }
    }
}