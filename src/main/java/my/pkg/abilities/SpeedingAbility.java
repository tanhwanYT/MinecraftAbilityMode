package my.pkg.abilities;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import my.pkg.AbilitySystem;

public class SpeedingAbility implements Ability {
    // 밸런스
    private static final int ACTIVE_TICKS = 20 * 5;  // 5초 활성
    private static final int SLOW_TICKS = 20 * 3;    // 3초 슬로우 패널티
    private static final Map<UUID, Integer> activeUntilTick = new ConcurrentHashMap<>();
    // 밀치기 쿨(연타 방지)
    private static final long BUMP_CD_MS = 350;
    private static final Map<UUID, Long> lastBumpAt = new ConcurrentHashMap<>();

    private static final int REWARD_SPEED_TICKS = 20 * 2;
    private static final Map<UUID, Boolean> touchedYellowWool = new ConcurrentHashMap<>();

    // 밀치기 세기
    private static final double BUMP_RANGE = 1.15;
    private static final double BUMP_POWER = 0.85; // xz 힘
    private static final double BUMP_Y = 0.25;     // 살짝 띄우기

    @Override
    public String id() { return "speeding"; }

    @Override
    public String name() { return "속도위반"; }

    @Override
    public int cooldownSeconds() { return 30; }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("§a속도위반 §7: 5초 동안 노란양털을 밟았을때 속도버프를 받습니다.");
        player.sendMessage("§7플레이어랑 부딫히면 상대를 날려버립니다. 끝나면 2초간 느려집니다");
        player.sendMessage("§7노란양털을 한 번도 밟지 않는다면 2초간 더 큰 신속을 얻습니다.");
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        int nowTick = (int) (system.getPlugin().getServer().getCurrentTick());
        Integer until = activeUntilTick.get(player.getUniqueId());
        if (until != null && until > nowTick) {
            player.sendActionBar("§7[속도위반] 이미 활성 중!");
            return false;
        }

        int endTick = nowTick + ACTIVE_TICKS;
        activeUntilTick.put(player.getUniqueId(), endTick);
        touchedYellowWool.put(player.getUniqueId(), false);

        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.8f);

        new BukkitRunnable() {
            @Override
            public void run() {
                // 혹시 중간에 다시 켰으면(끝 시간 갱신) 패널티 중복 방지
                Integer latest = activeUntilTick.get(player.getUniqueId());
                int curTick = (int) system.getPlugin().getServer().getCurrentTick();
                if (latest == null || latest > curTick) return;

                activeUntilTick.remove(player.getUniqueId());

                boolean violated = touchedYellowWool.getOrDefault(player.getUniqueId(), false);
                touchedYellowWool.remove(player.getUniqueId());

                if (player.isOnline() && !player.isDead()) {

                    if (violated) {
                        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, SLOW_TICKS, 2, false, false, true));
                        player.sendMessage("§c[속도위반] §f과속 단속! 3초간 감속...");
                        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f);
                    } else {
                        player.removePotionEffect(PotionEffectType.SPEED);

                        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, REWARD_SPEED_TICKS, 4, false, false, true));
                        player.sendMessage("§a[속도위반] §f과속단속을 잘 지켰습니다! §b신속 5§f를 받습니다.");
                        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.5f);
                    }
                }
            }
        }.runTaskLater(system.getPlugin(), ACTIVE_TICKS);

        return true; // 발동 성공 -> AbilitySystem이 쿨타임 시작
    }

    @Override
    public void onMove(AbilitySystem system, PlayerMoveEvent event) {
        Player p = event.getPlayer();

        // 활성 상태가 아니면 아무것도 안 함
        int nowTick = (int) system.getPlugin().getServer().getCurrentTick();
        Integer until = activeUntilTick.get(p.getUniqueId());
        if (until == null || until <= nowTick) return;

        // 발밑 블록 체크
        Material under = event.getTo().getBlock().getRelative(0, -1, 0).getType();
        if (under == Material.YELLOW_WOOL) {
            touchedYellowWool.put(p.getUniqueId(), true);

            // 활성 중에 노란 양털 밟으면 스피드 갱신
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20, 2, false, false, true));

            // ✅ 신속 중 충돌 밀치기
            tryBumpPlayers(p);
        }
    }

    private void tryBumpPlayers(Player p) {
        long now = System.currentTimeMillis();

        Long last = lastBumpAt.get(p.getUniqueId());
        if (last != null && now - last < BUMP_CD_MS) return; // 연타 방지

        // 주변 플레이어 중 가장 가까운 1명만 밀치기(과도한 OP 방지)
        Player closest = null;
        double best = BUMP_RANGE * BUMP_RANGE;

        for (Player other : p.getWorld().getPlayers()) {
            if (other.equals(p)) continue;
            if (!other.isOnline() || other.isDead()) continue;

            double d2 = other.getLocation().distanceSquared(p.getLocation());
            if (d2 <= best) {
                best = d2;
                closest = other;
            }
        }

        if (closest == null) return;

        // 방향: 내 진행 방향(시야) 기반으로 밀쳐내기
        // (정면 박치기 느낌이 강함)
        org.bukkit.util.Vector dir = p.getLocation().getDirection().setY(0).normalize();
        if (dir.lengthSquared() < 0.0001) return;

        org.bukkit.util.Vector knock = dir.multiply(BUMP_POWER);
        knock.setY(BUMP_Y);

        closest.setVelocity(knock);

        // 피드백
        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.6f, 1.3f);
        closest.playSound(closest.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_HURT, 0.4f, 1.4f);

        lastBumpAt.put(p.getUniqueId(), now);
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        touchedYellowWool.remove(player.getUniqueId());
        activeUntilTick.remove(player.getUniqueId());
        lastBumpAt.remove(player.getUniqueId());
        player.removePotionEffect(PotionEffectType.SPEED);
    }
}
