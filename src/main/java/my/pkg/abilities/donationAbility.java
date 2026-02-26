package my.pkg.abilities;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import my.pkg.AbilitySystem;

public class donationAbility implements Ability, Listener {

    // ====== 설정 ======
    private static final int RANGE = 10;            // 시선 대상 거리
    private static final int REQUIRED_SHIFT = 10;  // 쉬프트 몇 번 눌러야 해제?
    private static final int STUN_SECONDS = 20;     // 최대 스턴 시간(초). 0이면 시간제한 없음

    // ====== 상태 저장 (타겟 UUID -> 상태) ======
    private static final Map<UUID, DonateState> stunned = new ConcurrentHashMap<>();

    private static class DonateState {
        final UUID casterId;
        int shiftCount;
        final long expireAt; // ms

        DonateState(UUID casterId, int durationSeconds) {
            this.casterId = casterId;
            this.shiftCount = 0;
            this.expireAt = (durationSeconds <= 0)
                    ? Long.MAX_VALUE
                    : (System.currentTimeMillis() + durationSeconds * 1000L);
        }

        boolean expired() {
            return System.currentTimeMillis() >= expireAt;
        }
    }

    // ====== Listener 등록(중복 등록 방지) ======
    private static boolean listenerRegistered = false;

    public donationAbility(JavaPlugin plugin) {
        // ⚠️ 이 능력은 "타겟(상대)"의 이동/쉬프트를 막아야 해서
        // AbilitySystem이 아니라 전역 Listener로 이벤트를 받아야 함.
        if (!listenerRegistered) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            listenerRegistered = true;
        }
    }

    @Override
    public String id() { return "donation"; }

    @Override
    public String name() { return "도네이션"; }

    @Override
    public int cooldownSeconds() { return 25; }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("도네이션 : 바라보는 플레이어를 잠시 스턴시킵니다. 상대는 쉬프트를 여러 번 눌러야 해제됩니다.");
    }

    @Override
    public boolean activate(AbilitySystem system, Player caster) {
        Entity targetEntity = caster.getTargetEntity(RANGE, false);

        if (!(targetEntity instanceof Player target)) {
            caster.sendMessage("§c[도네이션] 바라보는 플레이어가 없습니다.");
            return false;
        }

        if (target.getUniqueId().equals(caster.getUniqueId())) {
            caster.sendMessage("§c[도네이션] 자기 자신에게는 사용할 수 없습니다.");
            return false;
        }

        // 스턴 적용
        stunned.put(target.getUniqueId(), new DonateState(caster.getUniqueId(), STUN_SECONDS));

        // 방송 후원 타이틀
        target.sendTitle(
                "§d💰 " + caster.getName() + "님이 1000원 후원!",
                "§f쉬프트를 §d" + REQUIRED_SHIFT + "번§f 눌러 해제!",
                10, 60, 10
        );

        // 연출
        target.playSound(target.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        target.setVelocity(new Vector(0, 0, 0)); // 밀림 방지

        caster.sendMessage("§a[도네이션] " + target.getName() + "에게 도네이션!");
        return true;
    }

    // ==============================
    // 전역 이벤트: 타겟 스턴/해제 처리
    // ==============================

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        DonateState state = stunned.get(p.getUniqueId());
        if (state == null) return;

        // 시간 만료 시 해제
        if (state.expired()) {
            stunned.remove(p.getUniqueId());
            p.sendTitle("§a해방!", "§7(시간 만료)", 5, 20, 5);
            return;
        }

        if (event.getTo() == null) return;

        // 이동 막기 (시선 회전은 허용)
        // 이동 막기 (X/Z만 고정, Y는 허용해서 낙하 가능 -> Fly 킥 방지)
        boolean movedXZ = event.getFrom().getX() != event.getTo().getX()
                || event.getFrom().getZ() != event.getTo().getZ();

        if (movedXZ) {
            var to = event.getTo().clone();
            to.setX(event.getFrom().getX());
            to.setZ(event.getFrom().getZ());
            event.setTo(to);
        }

    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        Player p = event.getPlayer();
        DonateState state = stunned.get(p.getUniqueId());
        if (state == null) return;

        // 시간 만료 시 해제
        if (state.expired()) {
            stunned.remove(p.getUniqueId());
            p.sendTitle("§a해방!", "§7(시간 만료)", 5, 20, 5);
            return;
        }

        // 쉬프트 "눌렀을 때"만 카운트 (떼면 false)
        if (!event.isSneaking()) return;

        state.shiftCount++;

        int left = Math.max(0, REQUIRED_SHIFT - state.shiftCount);
        p.sendMessage("§d[도네이션] §f쉬프트 " + state.shiftCount + "/" + REQUIRED_SHIFT + " §7(남은: " + left + ")");

        if (state.shiftCount >= REQUIRED_SHIFT) {
            stunned.remove(p.getUniqueId());
            p.sendTitle("§a해방!", "§7후원이 끝났습니다", 5, 25, 5);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.1f);
        }
    }
}
