package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BatmanAbility implements Ability, Listener {

    // ===== 밸런스 =====
    private static final double INITIAL_FIREBALL_SPEED = 0.85;
    private static final double SPEED_ADD_PER_HIT = 0.28;
    private static final double MAX_FIREBALL_SPEED = 2.6;
    private static final float FIREBALL_YIELD = 0.0f; // 폭발 파괴 X
    private static final int COOLDOWN_SECONDS = 14;

    // ===== 상태 =====
    private final JavaPlugin plugin;
    private final NamespacedKey batItemKey;
    private final NamespacedKey fireballOwnerKey;
    private final NamespacedKey fireballSpeedKey;
    private final NamespacedKey fireballHitCountKey;

    private final Map<UUID, UUID> activeBall = new ConcurrentHashMap<>();

    public BatmanAbility(JavaPlugin plugin) {
        this.plugin = plugin;
        this.batItemKey = new NamespacedKey(plugin, "batman_bat");
        this.fireballOwnerKey = new NamespacedKey(plugin, "batman_ball_owner");
        this.fireballSpeedKey = new NamespacedKey(plugin, "batman_ball_speed");
        this.fireballHitCountKey = new NamespacedKey(plugin, "batman_ball_hitcount");
    }

    @Override
    public String id() {
        return "batman";
    }

    @Override
    public String name() {
        return "4번타자";
    }

    @Override
    public int cooldownSeconds() {
        return COOLDOWN_SECONDS;
    }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        giveBat(player);
        player.sendMessage("4번타자 : 밀치기 2 배트를 받습니다. 능력 사용시 야구공(화염구)를 소환합니다.");
    }

    @Override
    public void onAttack(AbilitySystem system, EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof Player || event.getEntity() instanceof org.bukkit.entity.LivingEntity)) return;

        if (!isBatmanBat(attacker.getInventory().getItemInMainHand())) return;

        attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 1.25f);
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        UUID existingId = activeBall.get(player.getUniqueId());
        if (existingId != null) {
            Entity existing = Bukkit.getEntity(existingId);
            if (existing instanceof Fireball fb && !fb.isDead() && fb.isValid()) {
                player.sendMessage("§c[4번타자] 이미 야구공이 존재합니다!");
                return false;
            }
        }

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();

        Fireball ball = player.getWorld().spawn(eye.add(dir.clone().multiply(1.2)), Fireball.class, fb -> {
            fb.setShooter(player);

            // 처음엔 날아가지 않게 정지 상태로 소환
            fb.setDirection(new Vector(0, 0, 0));
            fb.setVelocity(new Vector(0, 0, 0));

            fb.setYield(FIREBALL_YIELD);
            fb.setIsIncendiary(false);
            fb.setVisualFire(false);

            fb.getPersistentDataContainer().set(fireballOwnerKey, PersistentDataType.STRING, player.getUniqueId().toString());
            fb.getPersistentDataContainer().set(fireballSpeedKey, PersistentDataType.DOUBLE, INITIAL_FIREBALL_SPEED);
            fb.getPersistentDataContainer().set(fireballHitCountKey, PersistentDataType.INTEGER, 0);
        });

        activeBall.put(player.getUniqueId(), ball.getUniqueId());

        World w = player.getWorld();
        w.playSound(player.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1.0f, 1.2f);
        w.spawnParticle(Particle.SMOKE, ball.getLocation(), 12, 0.15, 0.15, 0.15, 0.02);

        return true;
    }

    @EventHandler
    public void onHitFireball(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player hitter)) return;
        if (!(event.getEntity() instanceof Fireball ball)) return;

        if (!ball.getPersistentDataContainer().has(fireballOwnerKey, PersistentDataType.STRING)) return;
        if (!isBatmanBat(hitter.getInventory().getItemInMainHand())) return;

        event.setCancelled(true);

        Vector dir = hitter.getEyeLocation().getDirection().normalize();

        double prevSpeed = ball.getPersistentDataContainer()
                .getOrDefault(fireballSpeedKey, PersistentDataType.DOUBLE, INITIAL_FIREBALL_SPEED);

        int hitCount = ball.getPersistentDataContainer()
                .getOrDefault(fireballHitCountKey, PersistentDataType.INTEGER, 0);

        double nextSpeed = Math.min(MAX_FIREBALL_SPEED, prevSpeed + SPEED_ADD_PER_HIT);

        ball.setShooter(hitter);
        ball.setDirection(dir);
        ball.setVelocity(dir.multiply(nextSpeed));

        ball.getPersistentDataContainer().set(fireballSpeedKey, PersistentDataType.DOUBLE, nextSpeed);
        ball.getPersistentDataContainer().set(fireballHitCountKey, PersistentDataType.INTEGER, hitCount + 1);

        World w = hitter.getWorld();
        w.playSound(hitter.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 0.95f);
        w.playSound(hitter.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.45f, 1.45f);
        w.spawnParticle(Particle.SWEEP_ATTACK, hitter.getLocation().add(0, 1.0, 0), 1, 0, 0, 0, 0);
        w.spawnParticle(Particle.FLAME, ball.getLocation(), 10, 0.15, 0.15, 0.15, 0.01);

        hitter.sendActionBar("§6[4번타자] §e야구공 속도 Lv." + (hitCount + 1));
    }

    private boolean isBatmanBat(ItemStack item) {
        if (item == null) return false;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;

        NamespacedKey batKey = new NamespacedKey(plugin, "batman_bat");
        Byte v = meta.getPersistentDataContainer().get(batKey, PersistentDataType.BYTE);
        return v != null && v == (byte) 1;
    }

    private void giveBat(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (isBatmanBat(item)) return;
        }

        ItemStack bat = new ItemStack(Material.STICK);
        ItemMeta meta = bat.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§6배트");
            meta.addEnchant(org.bukkit.enchantments.Enchantment.KNOCKBACK, 2, true);
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
            meta.getPersistentDataContainer().set(batItemKey, PersistentDataType.BYTE, (byte) 1);
            bat.setItemMeta(meta);
        }

        player.getInventory().addItem(bat);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();

        if (isBatmanBat(item)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§c이 배트는 버릴 수 없습니다!");
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();

        if (event.getClick() == ClickType.DROP || event.getClick() == ClickType.CONTROL_DROP) {
            if (isBatmanBat(current)) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player player) {
                    player.sendMessage("§c이 배트는 버릴 수 없습니다!");
                }
                return;
            }
        }

        if (event.getSlot() == -999) {
            if (isBatmanBat(cursor) || isBatmanBat(current)) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player player) {
                    player.sendMessage("§c이 배트는 버릴 수 없습니다!");
                }
            }
        }
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        removeBatmanBat(player);

        UUID ballId = activeBall.remove(player.getUniqueId());
        if (ballId != null) {
            Entity e = Bukkit.getEntity(ballId);
            if (e != null && e.isValid()) {
                e.remove();
            }
        }
    }

    private void removeBatmanBat(Player player) {
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (isBatmanBat(item)) {
                player.getInventory().setItem(i, null);
            }
            ItemStack off = player.getInventory().getItemInOffHand();
            if (isBatmanBat(off)) {
                player.getInventory().setItemInOffHand(null);
            }
        }
    }
}