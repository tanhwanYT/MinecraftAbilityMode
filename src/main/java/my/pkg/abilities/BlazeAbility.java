package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Blaze;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BlazeAbility implements Ability, Listener {

    private final JavaPlugin plugin;

    private final Map<UUID, UUID> blazeMap = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> followTasks = new ConcurrentHashMap<>();
    private final Set<UUID> holders = ConcurrentHashMap.newKeySet();

    // 비행 게이지(틱 단위)
    private final Map<UUID, Integer> flightTicksLeft = new ConcurrentHashMap<>();

    private static boolean listenerRegistered = false;
    private static boolean loopStarted = false;

    private static final int COOLDOWN_SECONDS = 8;
    private static final double FIREBALL_SPEED = 1.2;

    // 비행 제한
    private static final int MAX_FLIGHT_TICKS = 20 * 6;      // 6초 비행 가능
    private static final int FLIGHT_REGEN_PER_TICK = 1;      // 땅에 있으면 초당 20틱 회복
    private static final int WATER_DAMAGE_PERIOD = 20;       // 1초마다
    private static final double WATER_DAMAGE = 2.0;          // 1초마다 1칸
    private static final int LOOP_PERIOD = 1;                // 1틱마다 체크

    public BlazeAbility(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "blaze";
    }

    @Override
    public String name() {
        return "블레이즈";
    }

    @Override
    public int cooldownSeconds() {
        return COOLDOWN_SECONDS;
    }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        holders.add(player.getUniqueId());
        flightTicksLeft.putIfAbsent(player.getUniqueId(), MAX_FLIGHT_TICKS);

        if (!listenerRegistered) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            listenerRegistered = true;
        }

        if (!loopStarted) {
            startMainLoop();
            loopStarted = true;
        }

        applyBlazeState(player);

        player.sendMessage("§6[블레이즈] §f당신은 블레이즈입니다.");
        player.sendMessage("§7- 투명, 화염저항 상태가 적용됩니다.");
        player.sendMessage("§7- 비행은 제한시간만큼만 가능합니다.");
        player.sendMessage("§7- 물에 닿으면 피해를 입습니다.");
        player.sendMessage("§7- 블록 설치/파괴가 불가능합니다.");
        player.sendMessage("§7- 블레이즈가 죽으면 당신도 죽습니다.");
        player.sendMessage("§7- 능력 사용 시 화염구를 발사합니다.");
        player.sendMessage("§7- 바지와 신발은 착용할 수 없습니다.");
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        holders.remove(player.getUniqueId());
        flightTicksLeft.remove(player.getUniqueId());
        clearBlazeState(player);
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        if (!holders.contains(player.getUniqueId())) {
            return false;
        }

        if (player.getGameMode() != GameMode.SURVIVAL) {
            player.sendMessage("§c[블레이즈] 서바이벌 상태에서만 사용할 수 있습니다.");
            return false;
        }

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();

        Fireball fireball = player.launchProjectile(Fireball.class);
        fireball.setShooter(player);
        fireball.setVelocity(dir.multiply(FIREBALL_SPEED));
        fireball.setYield(0.0f);
        fireball.setIsIncendiary(true);

        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1.0f, 1.0f);
        player.getWorld().spawnParticle(
                Particle.FLAME,
                eye.add(dir.clone().multiply(0.8)),
                20, 0.15, 0.15, 0.15, 0.02
        );

        return true;
    }

    private void applyBlazeState(Player player) {
        if (!holders.contains(player.getUniqueId())) return;
        if (player.getGameMode() != GameMode.SURVIVAL) return;

        player.addPotionEffect(new PotionEffect(
                PotionEffectType.INVISIBILITY,
                PotionEffect.INFINITE_DURATION,
                0,
                false,
                false,
                false
        ));
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.FIRE_RESISTANCE,
                PotionEffect.INFINITE_DURATION,
                0,
                false,
                false,
                false
        ));

        player.setAllowFlight(true);
        spawnOrReplaceBlaze(player);
        burnForbiddenArmor(player);
    }

    private void clearBlazeState(Player player) {
        UUID playerId = player.getUniqueId();

        BukkitTask oldTask = followTasks.remove(playerId);
        if (oldTask != null) {
            oldTask.cancel();
        }

        UUID blazeId = blazeMap.remove(playerId);
        if (blazeId != null) {
            Entity e = Bukkit.getEntity(blazeId);
            if (e != null && e.isValid()) {
                e.remove();
            }
        }

        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        player.removePotionEffect(PotionEffectType.FIRE_RESISTANCE);

        if (player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR) {
            player.setFlying(false);
            player.setAllowFlight(false);
        }
    }

    private void spawnOrReplaceBlaze(Player player) {
        UUID playerId = player.getUniqueId();

        UUID oldBlazeId = blazeMap.remove(playerId);
        if (oldBlazeId != null) {
            Entity old = Bukkit.getEntity(oldBlazeId);
            if (old != null && old.isValid()) {
                old.remove();
            }
        }

        BukkitTask oldTask = followTasks.remove(playerId);
        if (oldTask != null) {
            oldTask.cancel();
        }

        Blaze blaze = player.getWorld().spawn(player.getLocation().add(0, 1.1, 0), Blaze.class);
        blaze.setCustomName("§6" + player.getName() + "의 블레이즈");
        blaze.setCustomNameVisible(false);
        blaze.setAI(false);
        blaze.setAware(false);
        blaze.setCollidable(false);
        blaze.setGravity(false);
        blaze.setSilent(true);
        blaze.setRemoveWhenFarAway(false);

        blaze.setInvulnerable(false);
        blaze.setPersistent(true);
        blaze.setVisualFire(true);

        blazeMap.put(playerId, blaze.getUniqueId());
        startFollowTask(player, blaze);
    }

    private void startFollowTask(Player player, Blaze blaze) {
        UUID playerId = player.getUniqueId();

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) {
                if (blaze.isValid()) blaze.remove();
                BukkitTask t = followTasks.remove(playerId);
                if (t != null) t.cancel();
                blazeMap.remove(playerId);
                return;
            }

            if (!holders.contains(playerId)) {
                if (blaze.isValid()) blaze.remove();
                BukkitTask t = followTasks.remove(playerId);
                if (t != null) t.cancel();
                blazeMap.remove(playerId);
                return;
            }

            if (player.isDead()) return;

            if (player.getGameMode() != GameMode.SURVIVAL) {
                if (blaze.isValid()) blaze.remove();
                return;
            }

            if (!blaze.isValid() || blaze.isDead()) return;

            Vector back = player.getLocation().getDirection().clone().setY(0).normalize().multiply(-0.9);
            Location target = player.getLocation().clone().add(back).add(0, 2.0, 0);

            target.setYaw(player.getLocation().getYaw());
            target.setPitch(player.getLocation().getPitch());

            blaze.teleport(target);
        }, 0L, 1L);

        followTasks.put(playerId, task);
    }

    private void startMainLoop() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int tick = Bukkit.getCurrentTick();

            for (UUID uuid : holders) {
                Player player = Bukkit.getPlayer(uuid);
                if (player == null || !player.isOnline()) continue;
                if (player.isDead()) continue;
                if (player.getGameMode() != GameMode.SURVIVAL) continue;

                // 투명 유지
                if (!player.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
                    player.addPotionEffect(new PotionEffect(
                            PotionEffectType.INVISIBILITY,
                            40,
                            0,
                            false,
                            false,
                            false
                    ));
                }

                // 비행 제한
                int left = flightTicksLeft.getOrDefault(uuid, MAX_FLIGHT_TICKS);

                if (player.isFlying()) {
                    left = Math.max(0, left - 1);

                    if (left <= 0) {
                        player.setFlying(false);
                        player.setAllowFlight(false);
                        player.sendActionBar("§c[블레이즈] 비행 에너지가 바닥났습니다!");
                    } else {
                        player.setAllowFlight(true);
                        player.sendActionBar("§6[블레이즈] §f비행 연료: §e" + (left / 20.0) + "초");
                    }
                } else {
                    // 바닥에 있거나 비행 안 하면 회복
                    if (player.isOnGround()) {
                        left = Math.min(MAX_FLIGHT_TICKS, left + FLIGHT_REGEN_PER_TICK * LOOP_PERIOD);
                    }

                    // 연료가 남아 있으면 다시 비행 허용
                    if (left > 0) {
                        player.setAllowFlight(true);
                    }

                    player.sendActionBar("§6[블레이즈] §f비행 연료: §e" + (left / 20.0) + "초");
                }

                flightTicksLeft.put(uuid, left);

                // 물 대미지
                if (tick % WATER_DAMAGE_PERIOD == 0) {
                    if (isTouchingWater(player)) {
                        damageNoKnockback(player, WATER_DAMAGE);
                        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 0.8f, 1.2f);
                        player.getWorld().spawnParticle(
                                Particle.SMOKE,
                                player.getLocation().add(0, 1.0, 0),
                                12, 0.3, 0.4, 0.3, 0.02
                        );
                        player.sendMessage("§9[블레이즈] §f물이 너무 뜨겁지 않네요. 대미지를 입습니다.");
                    }
                }

                // 바지/신발 태우기
                burnForbiddenArmor(player);
            }
        }, 1L, LOOP_PERIOD);
    }

    private boolean isTouchingWater(Player player) {
        Material feet = player.getLocation().getBlock().getType();
        Material body = player.getLocation().clone().add(0, 1, 0).getBlock().getType();
        return isWater(feet) || isWater(body) || player.isInWater();
    }

    private boolean isWater(Material type) {
        return type == Material.WATER || type == Material.BUBBLE_COLUMN;
    }

    private void damageNoKnockback(Player player, double damage) {
        double remain = damage;

        double absorption = player.getAbsorptionAmount();
        if (absorption > 0) {
            double used = Math.min(absorption, remain);
            player.setAbsorptionAmount(absorption - used);
            remain -= used;
        }

        if (remain <= 0) return;

        double newHealth = Math.max(0.0, player.getHealth() - remain);
        player.setHealth(newHealth);
    }

    private void burnForbiddenArmor(Player player) {
        PlayerInventory inv = player.getInventory();

        // 착용 중인 방어구
        ItemStack boots = inv.getBoots();
        if (isForbiddenArmor(boots)) {
            inv.setBoots(null);
            burnNotice(player, "신발");
        }

        ItemStack leggings = inv.getLeggings();
        if (isForbiddenArmor(leggings)) {
            inv.setLeggings(null);
            burnNotice(player, "바지");
        }

        // 인벤토리 안에 들어온 바지/신발도 태움
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (isForbiddenArmor(item)) {
                inv.setItem(i, null);
                burnNotice(player, "방어구");
            }
        }
    }

    private boolean isForbiddenArmor(ItemStack item) {
        if (item == null) return false;

        Material type = item.getType();
        String name = type.name();

        return name.endsWith("_BOOTS") || name.endsWith("_LEGGINGS");
    }

    private void burnNotice(Player player, String part) {
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_GENERIC_BURN, 0.7f, 1.1f);
        player.getWorld().spawnParticle(
                Particle.FLAME,
                player.getLocation().add(0, 1.0, 0),
                12, 0.25, 0.4, 0.25, 0.02
        );
        player.sendActionBar("§6[블레이즈] §f" + part + "은(는) 불타 사라졌습니다.");
    }

    @EventHandler
    public void onBlazeDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Blaze blaze)) return;

        UUID deadBlazeId = blaze.getUniqueId();
        UUID ownerId = null;

        for (Map.Entry<UUID, UUID> entry : blazeMap.entrySet()) {
            if (entry.getValue().equals(deadBlazeId)) {
                ownerId = entry.getKey();
                break;
            }
        }

        if (ownerId == null) return;

        blazeMap.remove(ownerId);

        BukkitTask task = followTasks.remove(ownerId);
        if (task != null) {
            task.cancel();
        }

        Player player = Bukkit.getPlayer(ownerId);
        if (player != null && player.isOnline() && !player.isDead()) {
            player.sendMessage("§c[블레이즈] 당신의 블레이즈가 죽었습니다...");
            player.setHealth(0.0);
        }
    }

    @EventHandler
    public void onBlazeAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Blaze blaze)) return;

        if (blazeMap.containsValue(blaze.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!holders.contains(uuid)) return;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            if (player.getGameMode() != GameMode.SURVIVAL) return;

            flightTicksLeft.put(uuid, MAX_FLIGHT_TICKS);
            applyBlazeState(player);
        }, 1L);
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (holders.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar("§c[블레이즈] 블록을 설치할 수 없습니다.");
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (holders.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar("§c[블레이즈] 블록을 부술 수 없습니다.");
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!holders.contains(player.getUniqueId())) return;

        Bukkit.getScheduler().runTask(plugin, () -> burnForbiddenArmor(player));
    }

    @EventHandler
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (!holders.contains(player.getUniqueId())) return;

        Bukkit.getScheduler().runTask(plugin, () -> burnForbiddenArmor(player));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clearBlazeState(event.getPlayer());
    }
}