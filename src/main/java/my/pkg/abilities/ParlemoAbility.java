package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ParlemoAbility implements Ability {

    // ===== 밸런스 =====
    private static final long ENGAGE_WINDOW_MS = 6_000;     // 최근 6초 교전 상대 기준
    private static final int STACK_GAIN_ON_HIT = 1;         // 듀얼 상태에서 공격 1회당 +1
    private static final int STACK_DECAY_OUT_COMBAT = 1;    // 비전투 시 초당 -1
    private static final long OUT_COMBAT_MS = 7_000;        // 7초 교전 없으면 비전투로 판단

    private static final int STACK_MAX = 30;                // 스택 상한
    private static final int MULTI_PENALTY_BASE = 4;        // 다대1 기본 패널티
    private static final int MULTI_PENALTY_PER_EXTRA = 3;   // 추가 적 1명당 더 깎기 (기하급수 느낌)

    private static final int PARRY_REQ_STACK = 8;          // 8스택부터 패링 가능
    private static final double EARLY_DAMAGE_MULT = 0.9;   // 0~9스택 공격력 감소
    private static final int DAMAGE_GROWTH_START = 14;      // 14스택부터 공격력 증가 시작
    private static final int DAMAGE_GROWTH_STEP = 6;       // 6스택마다 증가
    private static final double DAMAGE_PER_STEP = 1.0;      // 단계당 +1 데미지
    private static final double MAX_BONUS_DAMAGE = 5.0;     // 최대 +4 데미지
        // 이 스택 이상부터 패링 가능
    private static final double PARRY_CHANCE = 0.20;        // 20%
    private static final double PARRY_COUNTER_DAMAGE = 1.0; // 패링 반격(0으로 하면 반격 없음)

    // ===== 상태 =====
    private final Map<UUID, Integer> stacks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastCombatAt = new ConcurrentHashMap<>();

    // 최근 교전 상대: (내 UUID) -> (상대 UUID -> 마지막 교전 시각)
    private final Map<UUID, Map<UUID, Long>> recentEnemies = new ConcurrentHashMap<>();

    private final JavaPlugin plugin;

    public ParlemoAbility(JavaPlugin plugin) {
        this.plugin = plugin;
        startDecayTask();
    }

    @Override public String id() { return "palermo"; }
    @Override public String name() { return "팔레르모"; }
    @Override public int cooldownSeconds() { return 0; } // 패시브 컨셉

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("팔레르모 : 1 VS 1 의 상황에서는 팔레르모를 배운 제가 더 유리합니다. 하지만 다수의 전투에서는 힘을 쓰기 어렵습니다.");
        player.sendMessage("§7- 스택 " + PARRY_REQ_STACK + " 이상: 20% 확률 패링(피해 무효)");
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        UUID id = player.getUniqueId();
        stacks.remove(id);
        lastCombatAt.remove(id);
        recentEnemies.remove(id);
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        player.sendMessage("§7[팔레르모] §f패시브 능력입니다.");
        return false;
    }

    @Override
    public void onAttack(AbilitySystem system, EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player me)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;

        UUID myId = me.getUniqueId();
        UUID enemyId = target.getUniqueId();

        touchCombat(myId);
        addEnemy(myId, enemyId);

        int enemyCount = getEnemyCount(myId);
        if (enemyCount == 1) {
            // 듀얼: 스택 증가
            if (isOnlyEnemy(myId, enemyId)) {
                addStack(myId, STACK_GAIN_ON_HIT);
                showStack(me);
            }
        } else {
            // 다대1: 기하급수적 약화 느낌으로 크게 깎음
            int penalty = MULTI_PENALTY_BASE + (enemyCount - 2) * MULTI_PENALTY_PER_EXTRA;
            addStack(myId, -penalty);
            me.sendActionBar("§c[팔레르모] §f여럿과 교전 중! 스택 감소 §c-" + penalty);
        }

        // ✅ 스택 기반 공격력 보정
        int stack = stacks.getOrDefault(myId, 0);

        // 0~9스택: 약함
        if (stack < 10) {
            event.setDamage(event.getDamage() * EARLY_DAMAGE_MULT);
            return;
        }

        // 10~29스택: 기본 공격력 그대로
        if (stack < DAMAGE_GROWTH_START) {
            return;
        }

        // 30스택부터 10스택마다 +데미지
        int steps = ((stack - DAMAGE_GROWTH_START) / DAMAGE_GROWTH_STEP) + 1;
        double bonusDamage = Math.min(MAX_BONUS_DAMAGE, steps * DAMAGE_PER_STEP);

        event.setDamage(event.getDamage() + bonusDamage);
    }

    // ✅ 내가 맞을 때: 교전 상대 기록 + 패링(조건: 듀얼 + 스택)
    @Override
    public void onDamage(AbilitySystem system, EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player me)) return;

        UUID myId = me.getUniqueId();
        touchCombat(myId);

        if (event instanceof EntityDamageByEntityEvent e) {
            Entity damager = e.getDamager();
            UUID enemyId = (damager instanceof LivingEntity le) ? le.getUniqueId() : null;
            if (enemyId != null) addEnemy(myId, enemyId);

            // 패링 조건 체크
            if (enemyId != null
                    && getEnemyCount(myId) == 1
                    && isOnlyEnemy(myId, enemyId)
                    && stacks.getOrDefault(myId, 0) >= PARRY_REQ_STACK) {

                if (Math.random() < PARRY_CHANCE) {
                    // ✅ 패링: 데미지 무효
                    e.setCancelled(true);

                    // 연출
                    Location loc = me.getLocation().add(0, 1.0, 0);
                    me.getWorld().playSound(loc, Sound.ITEM_SHIELD_BLOCK, 1.0f, 1.6f);
                    me.getWorld().spawnParticle(Particle.CRIT, loc, 30, 0.4, 0.6, 0.4, 0.1);

                    me.sendActionBar("§d[팔레르모] §f패링! §a(피해 무효)");

                    // (옵션) 반격 아주 조금 + 살짝 넉백
                    if (PARRY_COUNTER_DAMAGE > 0 && damager instanceof LivingEntity le) {
                        le.damage(PARRY_COUNTER_DAMAGE, me);
                        Vector kb = le.getLocation().toVector().subtract(me.getLocation().toVector()).normalize().multiply(0.4);
                        kb.setY(0.25);
                        le.setVelocity(kb);
                    }

                    // (옵션) 패링 성공 보상: 스택 약간 추가(원하면 0으로)
                    addStack(myId, 1);
                    showStack(me);
                }
            }
        }
    }

    // ===== 내부 로직 =====

    private void touchCombat(UUID id) {
        lastCombatAt.put(id, System.currentTimeMillis());
    }

    private void addEnemy(UUID myId, UUID enemyId) {
        long now = System.currentTimeMillis();
        Map<UUID, Long> m = recentEnemies.computeIfAbsent(myId, _ -> new ConcurrentHashMap<>());
        m.put(enemyId, now);
        cleanupEnemies(myId, now);
    }

    private void cleanupEnemies(UUID myId, long now) {
        Map<UUID, Long> m = recentEnemies.get(myId);
        if (m == null) return;
        m.entrySet().removeIf(en -> (now - en.getValue()) > ENGAGE_WINDOW_MS);
    }

    private int getEnemyCount(UUID myId) {
        long now = System.currentTimeMillis();
        cleanupEnemies(myId, now);
        Map<UUID, Long> m = recentEnemies.get(myId);
        return (m == null) ? 0 : m.size();
    }

    private boolean isOnlyEnemy(UUID myId, UUID enemyId) {
        Map<UUID, Long> m = recentEnemies.get(myId);
        if (m == null) return false;
        return m.size() == 1 && m.containsKey(enemyId);
    }

    private void addStack(UUID id, int delta) {
        int cur = stacks.getOrDefault(id, 0);
        int next = Math.max(0, Math.min(STACK_MAX, cur + delta));
        stacks.put(id, next);
    }

    private void showStack(Player p) {
        int s = stacks.getOrDefault(p.getUniqueId(), 0);

        String tier;
        if (s >= 30) {
            int steps = ((s - DAMAGE_GROWTH_START) / DAMAGE_GROWTH_STEP) + 1;
            double bonusDamage = Math.min(MAX_BONUS_DAMAGE, steps * DAMAGE_PER_STEP);
            tier = "§c§l(공격력 +" + bonusDamage + " / 패링 가능)";
        } else if (s >= PARRY_REQ_STACK) {
            tier = "§a§l(패링 가능)";
        } else if (s >= 10) {
            tier = "§e(기본 공격력 회복)";
        } else {
            tier = "§7(초반 약화)";
        }

        p.sendActionBar("§d[팔레르모] §f스택: §d" + s + "§f/" + STACK_MAX + " " + tier);
    }

    // 비전투 시 서서히 스택 감소(“전투가 길어지면 강해짐” 반대로, 전투 끊기면 약해짐)
    private void startDecayTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    UUID id = p.getUniqueId();

                    // 이 능력을 가진 플레이어만 깎고 싶으면 여기서 Ability 체크해도 됨
                    if (!stacks.containsKey(id)) continue;

                    long last = lastCombatAt.getOrDefault(id, 0L);
                    if (now - last < OUT_COMBAT_MS) continue;

                    int s = stacks.getOrDefault(id, 0);
                    if (s <= 0) continue;

                    addStack(id, -STACK_DECAY_OUT_COMBAT);
                    // 너무 스팸이면 주석
                    // showStack(p);
                }
            }
        }.runTaskTimer(plugin, 20L, 20L); // 1초마다
    }
}