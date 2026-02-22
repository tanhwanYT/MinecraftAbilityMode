package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BombermanAbility implements Ability {

    // ===== 밸런스 설정 =====
    private static final int COOLDOWN = 15;

    private static final int CHARGE_INTERVAL_TICKS = 10 * 20; // 10초마다 1개 적립
    private static final int MAX_CHARGES = 10;               // 최대 적립 개수

    private static final int FUSE_TICKS = 40;   // 2초
    private static final float YIELD = 4.0f;    // 폭발 범위

    private static final double SPAWN_RADIUS = 1.8; // 플레이어 주변 소환 반경
    private static final boolean CIRCLE_SPAWN = true; // true: 원형으로 균등배치, false: 랜덤

    // ===== 플레이어별 상태 =====
    private static final Map<UUID, Integer> charges = new ConcurrentHashMap<>();
    private static final Map<UUID, BukkitTask> chargeTasks = new ConcurrentHashMap<>();

    @Override
    public String id() { return "bomberman"; }

    @Override
    public String name() { return "붐버맨"; }

    @Override
    public int cooldownSeconds() { return COOLDOWN; }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("봄버맨 : 시간이 지날수록 TNT가 적립됩니다. 능력 사용 시 적립된 TNT를 전부 소환합니다");
        player.sendMessage("§7- 적립: " + (CHARGE_INTERVAL_TICKS / 20) + "초마다 +1 (최대 " + MAX_CHARGES + "개)");
        player.sendMessage("§7- 폭발 피해 면역 유지");

        startChargeTask(system, player);
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        stopChargeTask(player);
        charges.remove(player.getUniqueId());
    }

    @Override
    public void onDamage(AbilitySystem system, EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {

            event.setDamage(0.0);
        }
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        UUID id = player.getUniqueId();
        int n = charges.getOrDefault(id, 0);

        if (n <= 0) {
            player.sendMessage("§7[붐버맨] §f적립된 TNT가 없습니다!");
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 0.8f);
            return false; // 발동안함(쿨타임 소비 X)
        }

        // 사용 시 전부 소모
        charges.put(id, 0);

        Location base = player.getLocation().clone();
        player.getWorld().playSound(base, Sound.ENTITY_TNT_PRIMED, 1.0f, 1.0f);

        spawnManyTnt(player, base, n);

        return true;
    }

    // ===================== 내부 로직 =====================

    private void startChargeTask(AbilitySystem system, Player player) {
        UUID id = player.getUniqueId();

        // 혹시 이전 작업이 남아있으면 정리
        stopChargeTask(player);

        // 초기값 보장
        charges.putIfAbsent(id, 0);

        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    stopChargeTask(player);
                    charges.remove(id);
                    cancel();
                    return;
                }

                int cur = charges.getOrDefault(id, 0);
                if (cur >= MAX_CHARGES) return;

                cur++;
                charges.put(id, cur);

                // 액션바로 작게 표시 (원하면 채팅으로 바꿔도 됨)
                player.sendActionBar("§c[TNT 적립] §f" + cur + " / " + MAX_CHARGES);

                // 적립 느낌 사운드
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.6f);
            }
        }.runTaskTimer(system.getPlugin(), CHARGE_INTERVAL_TICKS, CHARGE_INTERVAL_TICKS);

        chargeTasks.put(id, task);
    }

    private void stopChargeTask(Player player) {
        UUID id = player.getUniqueId();
        BukkitTask task = chargeTasks.remove(id);
        if (task != null) task.cancel();
    }

    private void spawnManyTnt(Player owner, Location base, int count) {
        // 바닥에 파묻히는 거 방지
        base = base.clone().add(0, 0.1, 0);

        if (CIRCLE_SPAWN) {
            // 원형으로 균등 배치
            for (int i = 0; i < count; i++) {
                double angle = (2.0 * Math.PI) * i / count;
                double x = Math.cos(angle) * SPAWN_RADIUS;
                double z = Math.sin(angle) * SPAWN_RADIUS;

                Location loc = base.clone().add(x, 0, z);
                spawnPrimedTnt(owner, loc);
            }
        } else {
            // 랜덤 배치
            for (int i = 0; i < count; i++) {
                double angle = Math.random() * 2.0 * Math.PI;
                double radius = 0.8 + Math.random() * (SPAWN_RADIUS - 0.8);
                double x = Math.cos(angle) * radius;
                double z = Math.sin(angle) * radius;

                Location loc = base.clone().add(x, 0, z);
                spawnPrimedTnt(owner, loc);
            }
        }
    }

    private void spawnPrimedTnt(Player owner, Location loc) {
        TNTPrimed tnt = owner.getWorld().spawn(loc, TNTPrimed.class);
        tnt.setFuseTicks(FUSE_TICKS);
        tnt.setSource(owner);
        tnt.setYield(YIELD);
        tnt.setIsIncendiary(false);

        // 살짝 튀는 느낌(원하면 삭제)
        Vector kick = loc.toVector().subtract(owner.getLocation().toVector()).normalize().multiply(0.12);
        tnt.setVelocity(kick);
    }
}
