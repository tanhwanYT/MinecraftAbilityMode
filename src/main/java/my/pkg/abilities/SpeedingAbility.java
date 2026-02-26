package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SpeedingAbility implements Ability {
    // 밸런스
    private static final int ACTIVE_TICKS = 20 * 5;  // 5초 활성
    private static final int SLOW_TICKS = 20 * 4;    // 4초 슬로우 패널티
    private static final Map<UUID, Integer> activeUntilTick = new ConcurrentHashMap<>();
    @Override
    public String id() { return "speeding"; }

    @Override
    public String name() { return "속도위반"; }

    @Override
    public int cooldownSeconds() { return 25; }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        // 사용법 안내
        player.sendMessage("속도위반 : 5초 동안 노란양털을 밟았을때 속도버프를 받습니다. 끝나면 2초간 느려집니다");
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

        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.8f);

        // 5초 후 패널티(슬로우 2초) + 비활성화
        new BukkitRunnable() {
            @Override
            public void run() {
                // 혹시 중간에 다시 켰으면(끝 시간 갱신) 패널티 중복 방지
                Integer latest = activeUntilTick.get(player.getUniqueId());
                int curTick = (int) system.getPlugin().getServer().getCurrentTick();
                if (latest == null || latest > curTick) return;

                activeUntilTick.remove(player.getUniqueId());

                if (player.isOnline() && !player.isDead()) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, SLOW_TICKS, 0, false, false, true));
                    player.sendMessage("§c[속도위반] §f과속 단속! 4초간 감속...");
                    player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f);
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
            // 활성 중에 노란 양털 밟으면 스피드 갱신
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20, 2, false, false, true));
        }
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        activeUntilTick.remove(player.getUniqueId());
        // 남아있을 수 있는 효과 정리(선택)
        player.removePotionEffect(PotionEffectType.SPEED);
    }
}
