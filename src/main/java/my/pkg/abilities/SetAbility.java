package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SetAbility implements Ability {

    // ===== 밸런스 설정 =====
    private static final int MAX_STACK = 10;

    private static final long COMBAT_GRACE_MS = 6_000;
    private static final int DECAY_INTERVAL_TICKS = 20;

    private static final double RANGE = 7.0;
    private static final double FAN_ANGLE_DEG = 110.0;
    private static final double CENTER_ANGLE_DEG = 12.0;

    private static final double DAMAGE_PER_STACK = 1.0;
    private static final double FLAT_DAMAGE_BONUS = 3.0; // 전 구간 데미지 +3
    private static final double CENTER_MULT = 1.6;

    private static final int CAST_TICKS = 20;

    private static final double HURT_STACK_PER_DAMAGE = 0.25;
    private static final int HURT_MIN_STACK = 1;
    private static final int HURT_MAX_PER_HIT = 2;

    // 보호막
    private static final double ABSORPTION_PER_STACK = 1.0; // 1스택당 반칸
    private static final double MAX_ABSORPTION = 10.0;      // 최대 5칸

    // ===== 상태 =====
    private final JavaPlugin plugin;

    private final Map<UUID, Integer> stacks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastCombat = new ConcurrentHashMap<>();
    private final Set<UUID> casting = ConcurrentHashMap.newKeySet();

    private BukkitRunnable tickTask;

    public SetAbility(JavaPlugin plugin) {
        this.plugin = plugin;
        startTickTask();
    }

    @Override
    public String id() { return "set"; }

    @Override
    public String name() { return "세트"; }

    @Override
    public int cooldownSeconds() { return 25; }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("세트 : 피격 시 투지 스택을 쌓습니다. 능력사용시 1초 후 전방에 투지스택에 비례한 부채꼴 공격을 가합니다.");
    }

    /**
     * 공격할 때는 이제 투지가 차지 않음
     */
    @Override
    public void onAttack(AbilitySystem system, EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;

        // 공격 시 투지는 쌓지 않음
        markCombat(attacker);
    }

    @Override
    public void onDamage(AbilitySystem system, EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        double dmg = event.getFinalDamage();
        if (dmg <= 0) return;

        int gain = (int) Math.floor(dmg * HURT_STACK_PER_DAMAGE);
        gain = Math.max(HURT_MIN_STACK, gain);
        gain = Math.min(HURT_MAX_PER_HIT, gain);

        addStack(victim, gain);
        markCombat(victim);
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        UUID id = player.getUniqueId();
        int s = stacks.getOrDefault(id, 0);

        if (s <= 0) {
            player.sendMessage("§c[세트] 투지가 없습니다!");
            return false;
        }
        if (casting.contains(id)) {
            player.sendMessage("§c[세트] 이미 시전 중입니다!");
            return false;
        }

        casting.add(id);

        // 이번 시전에 사용할 스택 고정
        final int castStack = s;

        // ✅ 클릭 즉시 노란 체력 지급
        double absorption = Math.min(castStack * ABSORPTION_PER_STACK, MAX_ABSORPTION);

        AttributeInstance maxAbs = player.getAttribute(Attribute.MAX_ABSORPTION);
        if (maxAbs != null && maxAbs.getBaseValue() < MAX_ABSORPTION) {
            maxAbs.setBaseValue(MAX_ABSORPTION);
        }

        player.setAbsorptionAmount(absorption);

        // 시전 시작 위치 + 시야 고정
        final Location lock = player.getLocation().clone();
        final float lockedYaw = lock.getYaw();
        final float lockedPitch = lock.getPitch();

        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, CAST_TICKS + 2, 10, false, false, true));
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_IRON_GOLEM_ATTACK, 1.0f, 0.9f);

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead()) {
                    casting.remove(id);
                    cancel();
                    return;
                }

                Location cur = player.getLocation();
                if (!cur.getWorld().equals(lock.getWorld())) {
                    casting.remove(id);
                    cancel();
                    return;
                }

                double y = cur.getY();

                // X/Z + 시야 고정
                Location fixed = new Location(lock.getWorld(), lock.getX(), y, lock.getZ(), lockedYaw, lockedPitch);

                if (cur.distanceSquared(fixed) > 0.0009) {
                    player.teleport(fixed);
                } else {
                    // 위치가 같아도 시야는 강제로 유지
                    if (Math.abs(cur.getYaw() - lockedYaw) > 0.1f || Math.abs(cur.getPitch() - lockedPitch) > 0.1f) {
                        player.teleport(fixed);
                    }
                }

                if (t % 2 == 0) {
                    showTelegraph(player);
                }

                if (t >= CAST_TICKS) {
                    int stackNow = stacks.getOrDefault(id, 0);
                    if (stackNow > 0) {
                        slam(player, stackNow);
                        stacks.put(id, 0);
                        player.sendActionBar("§6투지 §e0§7/§e" + MAX_STACK);
                    }

                    casting.remove(id);
                    cancel();
                    return;
                }

                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);

        return true;
    }

    // ===== 투지 로직 =====

    private void addStack(Player p, int amount) {
        UUID id = p.getUniqueId();
        int cur = stacks.getOrDefault(id, 0);
        int next = Math.min(MAX_STACK, cur + amount);
        stacks.put(id, next);

        p.sendActionBar("§6투지 §e" + next + "§7/§e" + MAX_STACK);
    }

    private void markCombat(Player p) {
        lastCombat.put(p.getUniqueId(), System.currentTimeMillis());
    }

    private void startTickTask() {
        if (tickTask != null) return;

        tickTask = new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();

                // 비전투 시 감소
                for (UUID id : new ArrayList<>(stacks.keySet())) {
                    int s = stacks.getOrDefault(id, 0);
                    if (s <= 0) continue;

                    long last = lastCombat.getOrDefault(id, 0L);
                    if (now - last >= COMBAT_GRACE_MS) {
                        int next = Math.max(0, s - 1);
                        stacks.put(id, next);
                    }
                }

                // 파티클 표시
                for (Player p : Bukkit.getOnlinePlayers()) {
                    int s = stacks.getOrDefault(p.getUniqueId(), 0);
                    if (s <= 0) continue;
                    showAngryStackParticles(p, s);
                }
            }
        };

        tickTask.runTaskTimer(plugin, 0L, DECAY_INTERVAL_TICKS);
    }

    // ===== 공격 =====

    private void slam(Player caster, int stack) {
        Location origin = caster.getLocation().clone();
        World w = caster.getWorld();

        double baseDamage = stack * DAMAGE_PER_STACK + FLAT_DAMAGE_BONUS;
        double halfFan = FAN_ANGLE_DEG / 2.0;
        double halfCenter = CENTER_ANGLE_DEG / 2.0;

        Vector forward = origin.getDirection().setY(0).normalize();

        w.playSound(origin, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.8f);

        for (Entity e : w.getNearbyEntities(origin, RANGE, 3.0, RANGE)) {
            if (!(e instanceof LivingEntity target)) continue;
            if (target.isDead()) continue;
            if (target.equals(caster)) continue;

            Location tl = target.getLocation();
            Vector to = tl.toVector().subtract(origin.toVector());
            double dist = to.length();
            if (dist > RANGE) continue;

            to.setY(0);
            if (to.lengthSquared() < 0.0001) continue;
            to.normalize();

            double angle = angleDeg(forward, to);
            if (angle > halfFan) continue;

            double dmg = baseDamage;
            if (angle <= halfCenter) dmg *= CENTER_MULT;

            double finalHealth = target.getHealth() - dmg;
            target.setHealth(Math.max(0, finalHealth));
        }
    }

    private static double angleDeg(Vector a, Vector b) {
        double dot = a.dot(b);
        dot = Math.max(-1.0, Math.min(1.0, dot));
        return Math.toDegrees(Math.acos(dot));
    }

    // ===== 가시화 =====

    private void showAngryStackParticles(Player p, int stack) {
        World w = p.getWorld();
        Location base = p.getLocation().clone().add(0, 2.0, 0);

        int n = Math.min(stack, 10);
        double radius = 0.35;

        for (int i = 0; i < n; i++) {
            double ang = (2 * Math.PI) * (i / (double) n);
            double x = Math.cos(ang) * radius;
            double z = Math.sin(ang) * radius;
            Location loc = base.clone().add(x, 0, z);
            w.spawnParticle(Particle.ANGRY_VILLAGER, loc, 1, 0, 0, 0, 0);
        }
    }

    private void showTelegraph(Player caster) {
        World w = caster.getWorld();
        Location origin = caster.getLocation().clone();
        origin.setY(origin.getY() + 0.1);

        Vector forward = origin.getDirection().setY(0).normalize();

        double halfFan = FAN_ANGLE_DEG / 2.0;
        double stepR = 0.55;
        double stepDeg = 10.0;

        for (double deg = -halfFan; deg <= halfFan; deg += stepDeg) {
            Vector dir = rotateYaw(forward, deg);
            Location edge = origin.clone().add(dir.multiply(RANGE));
            w.spawnParticle(Particle.CRIT, edge, 1, 0, 0, 0, 0);
        }

        for (double r = 1.0; r <= RANGE; r += stepR) {
            for (double deg = -halfFan; deg <= halfFan; deg += stepDeg * 1.5) {
                Vector dir = rotateYaw(forward, deg);
                Location p = origin.clone().add(dir.multiply(r));
                w.spawnParticle(Particle.SWEEP_ATTACK, p, 1, 0, 0, 0, 0);
            }
        }

        for (double r = 1.0; r <= RANGE; r += 0.35) {
            Location p = origin.clone().add(forward.clone().multiply(r));
            w.spawnParticle(Particle.CRIT, p, 1, 0, 0, 0, 0);
        }
    }

    private static Vector rotateYaw(Vector v, double degrees) {
        double rad = Math.toRadians(degrees);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        double x = v.getX() * cos - v.getZ() * sin;
        double z = v.getX() * sin + v.getZ() * cos;
        return new Vector(x, 0, z).normalize();
    }
}