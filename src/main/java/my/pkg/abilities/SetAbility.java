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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SetAbility implements Ability {

    // ===== 밸런스 설정 =====
    private static final int MAX_STACK = 10;

    private static final long COMBAT_GRACE_MS = 6_000; // 6초 비전투면 감소 시작
    private static final int DECAY_INTERVAL_TICKS = 20; // 1초마다

    private static final double RANGE = 6.0;            // 부채꼴 반경
    private static final double FAN_ANGLE_DEG = 90.0;   // 부채꼴 전체 각도
    private static final double CENTER_ANGLE_DEG = 12.0; // 중앙선 판정 각도(좁게)
    private static final double DAMAGE_PER_STACK = 1.0; // "반 하트" 단위 아님: Bukkit damage는 1.0 = 반칸(하트 0.5)
    private static final double CENTER_MULT = 1.6;      // 중앙선 추가 배율

    private static final int CAST_TICKS = 20; // 1초

    private static final double HURT_STACK_PER_DAMAGE = 0.25; // 맞을 때 계수(낮게)
    private static final int HURT_MIN_STACK = 1;              // 최소 1은 쌓이게
    private static final int HURT_MAX_PER_HIT = 2;            // 한 번에 최대 2까지만(폭사 방지)

    // ===== 상태 =====
    private final JavaPlugin plugin;

    // 투지 스택 / 마지막 전투 시각 / 시전중 여부
    private final Map<UUID, Integer> stacks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastCombat = new ConcurrentHashMap<>();
    private final Set<UUID> casting = ConcurrentHashMap.newKeySet();

    // 투지 가시화(주변에 화나는 주민 파티클) + 감소 처리용 반복 태스크 (1개만)
    private BukkitRunnable tickTask;

    public SetAbility(JavaPlugin plugin) {
        this.plugin = plugin;
        startTickTask();
    }

    @Override public String id() { return "set"; }
    @Override public String name() { return "세트"; }
    @Override public int cooldownSeconds() { return 20; } // 원하는 값으로

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("세트 : 전투로 투지 스택을 쌓습니다. 능력사용시 1초 후 전방에 투지스택에 비례한 부채꼴 공격을 가합니다.");
    }

    /**
     * PVP 공격 성공 시 호출되게 연결 (네 AbilitySystem이 onAttack으로 넘겨주는 구조 기준)
     */
    @Override
    public void onAttack(AbilitySystem system, EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;

        // 내가 때리면(플레이어/몹/동물 상관없이) 투지 +1
        addStack(attacker, 1);
        markCombat(attacker);

    }

    @Override
    public void onDamage(AbilitySystem system, EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        // 최종 피해량 기준(방어구/포션/감소 다 반영된 값)
        double dmg = event.getFinalDamage();
        if (dmg <= 0) return;

        // 피해량 기반으로 "적게" 쌓이게
        // 예: 최종피해 4.0(하트2칸) -> 4.0 * 0.25 = 1스택
        int gain = (int) Math.floor(dmg * HURT_STACK_PER_DAMAGE);

        // 너무 적게 들어오면 0이 되니까 최소 보정(원하면 HURT_MIN_STACK=0으로)
        gain = Math.max(HURT_MIN_STACK, gain);

        // 한 번에 과도하게 쌓이지 않도록 상한
        gain = Math.min(HURT_MAX_PER_HIT, gain);

        addStack(victim, gain);
        markCombat(victim);
    }

    /**
     * 액티브 발동(예: 우클릭)
     */
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

        // ✅ 시전 시작 위치 잠금(X/Z만)
        final Location lock = player.getLocation().clone();

        // 1초 시전 동안 움직임 제어(추가로 슬로우)
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

                // ✅ 수평 위치 고정 (Y는 현재 값 유지)
                Location cur = player.getLocation();
                if (!cur.getWorld().equals(lock.getWorld())) {
                    // 월드가 바뀌는 상황이면 그냥 취소(거의 없음)
                    casting.remove(id);
                    cancel();
                    return;
                }
                double y = cur.getY();
                float yaw = cur.getYaw();
                float pitch = cur.getPitch();

                // 너무 자주 텔포하면 시야 흔들림이 있을 수 있어서,
                // yaw/pitch는 현재값 유지, x/z만 고정
                Location fixed = new Location(lock.getWorld(), lock.getX(), y, lock.getZ(), yaw, pitch);

                // X/Z가 조금이라도 벗어났을 때만 텔포 (불필요한 텔포 줄이기)
                if (cur.distanceSquared(fixed) > 0.0009) { // 약 0.03블록 이상 차이
                    player.teleport(fixed);
                }

                // 시전 중 범위 표시(2틱마다)
                if (t % 2 == 0) {
                    showTelegraph(player);
                }

                if (t >= CAST_TICKS) {
                    int stackNow = stacks.getOrDefault(id, 0);
                    if (stackNow > 0) {
                        slam(player, stackNow);
                        stacks.put(id, 0);
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

        // 즉시 피드백 (본인)
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

                // 1) 비전투 감소
                for (UUID id : new ArrayList<>(stacks.keySet())) {
                    int s = stacks.getOrDefault(id, 0);
                    if (s <= 0) continue;

                    long last = lastCombat.getOrDefault(id, 0L);
                    if (now - last >= COMBAT_GRACE_MS) {
                        int next = Math.max(0, s - 1);
                        stacks.put(id, next);
                    }
                }

                // 2) 스택 가시화(주변 플레이어에게)
                for (Player p : Bukkit.getOnlinePlayers()) {
                    int s = stacks.getOrDefault(p.getUniqueId(), 0);
                    if (s <= 0) continue;
                    showAngryStackParticles(p, s);
                }
            }
        };

        tickTask.runTaskTimer(plugin, 0L, DECAY_INTERVAL_TICKS);
    }

    // ===== 공격(부채꼴 + 중앙선 강화) =====

    private void slam(Player caster, int stack) {
        Location origin = caster.getLocation().clone();
        World w = caster.getWorld();

        double baseDamage = stack * DAMAGE_PER_STACK;
        double halfFan = FAN_ANGLE_DEG / 2.0;
        double halfCenter = CENTER_ANGLE_DEG / 2.0;

        Vector forward = origin.getDirection().setY(0).normalize();

        w.playSound(origin, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.8f);

        // 반경 내 엔티티 탐색 (LivingEntity만)
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

            target.damage(dmg, caster);
        }
    }

    private static double angleDeg(Vector a, Vector b) {
        double dot = a.dot(b);
        dot = Math.max(-1.0, Math.min(1.0, dot));
        return Math.toDegrees(Math.acos(dot));
    }

    // ===== 가시화 (텔레그래프 / 스택 파티클) =====

    private void showAngryStackParticles(Player p, int stack) {
        World w = p.getWorld();
        Location base = p.getLocation().clone().add(0, 2.0, 0); // 머리 위

        // 스택을 "개수"로 보이게: 최대 10개까지 원형 배치
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
        double stepR = 0.55;     // 반경 샘플링 간격
        double stepDeg = 10.0;   // 호(arc) 각도 샘플링

        // 1) 부채꼴 외곽선(호)
        for (double deg = -halfFan; deg <= halfFan; deg += stepDeg) {
            Vector dir = rotateYaw(forward, deg);
            Location edge = origin.clone().add(dir.multiply(RANGE));
            w.spawnParticle(Particle.CRIT, edge, 1, 0, 0, 0, 0);
        }

        // 2) 부채꼴 내부를 살짝 채움(가시성 up)
        for (double r = 1.0; r <= RANGE; r += stepR) {
            for (double deg = -halfFan; deg <= halfFan; deg += stepDeg * 1.5) {
                Vector dir = rotateYaw(forward, deg);
                Location p = origin.clone().add(dir.multiply(r));
                w.spawnParticle(Particle.SWEEP_ATTACK, p, 1, 0, 0, 0, 0);
            }
        }

        // 3) 중앙 정면 1자(더 강함) 라인 - 더 촘촘하게
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