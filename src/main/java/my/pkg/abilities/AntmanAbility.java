package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class AntmanAbility implements Ability {

    // 15초 유지
    private static final int DURATION_TICKS = 15 * 20;

    // 원복을 위한 저장
    private static class PrevState {
        final double scale;
        final double maxHealth;
        PrevState(double scale, double maxHealth) {
            this.scale = scale;
            this.maxHealth = maxHealth;
        }
    }

    // 플레이어별 원래 상태 + 롤백 태스크
    private final ConcurrentHashMap<UUID, PrevState> prev = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> rollbackTaskId = new ConcurrentHashMap<>();

    @Override
    public String id() { return "antman"; }

    @Override
    public String name() { return "앤트맨"; }

    @Override
    public int cooldownSeconds() { return 25; }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("앤트맨 : 본인의 크기를 랜덤으로 조절합니다. 크기가 커지면 최대체력이 증가하고, 크기가 작아지면 신속 버프를 얻습니다. (15초 후 원상복구)");
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        UUID id = player.getUniqueId();

        // 기존 롤백 예약이 있으면 취소(갱신)
        Integer oldTask = rollbackTaskId.remove(id);
        if (oldTask != null) system.getPlugin().getServer().getScheduler().cancelTask(oldTask);

        // "원래 상태" 저장은 최초 1회만 (효과 유지 중 재사용해도 원래 값은 유지)
        prev.computeIfAbsent(id, k -> {
            double curScale = 1.0;
            AttributeInstance sAttr = player.getAttribute(Attribute.SCALE);
            if (sAttr != null) curScale = sAttr.getBaseValue();
            double curMax = player.getMaxHealth();
            return new PrevState(curScale, curMax);
        });

        // 랜덤 스케일
        double scale = ThreadLocalRandom.current().nextDouble(0.4, 1.61);

        // 스케일 적용
        AttributeInstance scaleAttr = player.getAttribute(Attribute.SCALE);
        if (scaleAttr != null) scaleAttr.setBaseValue(scale);

        // 최대체력 적용 + 현재체력 비율 유지
        AttributeInstance healthAttr = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = player.getMaxHealth();

        if (healthAttr != null) {
            double oldMax = player.getMaxHealth();
            double oldHp = player.getHealth();
            double ratio = (oldMax > 0.0) ? (oldHp / oldMax) : 1.0;

            double newMax = Math.max(4.0, 20.0 * scale);

            healthAttr.setBaseValue(newMax);

            double newHp = newMax * ratio;
            if (newHp < 1.0) newHp = 1.0;
            if (newHp > newMax) newHp = newMax;

            player.setHealth(newHp);
            maxHealth = newMax;
        }

        // ✅ 버프: 성급함(HASTE) 제거하고 신속(SPEED)로 교체
        player.removePotionEffect(PotionEffectType.HASTE);
        player.removePotionEffect(PotionEffectType.SPEED);

        if (scale < 1.0) {
            int amp;
            if (scale < 0.6) amp = 2;      // III
            else if (scale < 0.8) amp = 1; // II
            else amp = 0;                  // I

            // 15초 동안만 유지
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
                    DURATION_TICKS, amp, true, false, true));
        }

        player.sendMessage(String.format("§a[앤트맨] 크기: %.2f / 최대체력: %.0f (15초 후 원복)", scale, maxHealth));

        // ✅ 15초 후 원복 예약
        int taskId = system.getPlugin().getServer().getScheduler().runTaskLater(system.getPlugin(), () -> {
            rollback(system, player);
        }, DURATION_TICKS).getTaskId();

        rollbackTaskId.put(id, taskId);

        return true;
    }

    private void rollback(AbilitySystem system, Player player) {
        UUID id = player.getUniqueId();

        PrevState st = prev.remove(id);
        rollbackTaskId.remove(id);

        if (st == null) return;
        if (!player.isOnline()) return;

        // 현재 체력 비율 유지해서 원래 최대체력으로 복귀
        double curMax = player.getMaxHealth();
        double curHp = player.getHealth();
        double ratio = (curMax > 0.0) ? (curHp / curMax) : 1.0;

        AttributeInstance scaleAttr = player.getAttribute(Attribute.SCALE);
        if (scaleAttr != null) scaleAttr.setBaseValue(st.scale);

        AttributeInstance healthAttr = player.getAttribute(Attribute.MAX_HEALTH);
        if (healthAttr != null) {
            healthAttr.setBaseValue(st.maxHealth);

            double newHp = st.maxHealth * ratio;
            if (newHp < 1.0) newHp = 1.0;
            if (newHp > st.maxHealth) newHp = st.maxHealth;
            player.setHealth(newHp);
        } else {
            // 혹시 모를 안전 처리
            if (player.getHealth() > player.getMaxHealth()) player.setHealth(player.getMaxHealth());
        }

        // 버프 제거
        player.removePotionEffect(PotionEffectType.SPEED);

        player.sendMessage("§7[앤트맨] 효과가 끝나 원래 상태로 돌아왔습니다.");
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        UUID id = player.getUniqueId();

        Integer task = rollbackTaskId.remove(id);
        if (task != null) system.getPlugin().getServer().getScheduler().cancelTask(task);

        // 원래 상태로 강제 복귀(저장된 게 있으면 그걸로, 없으면 기본값)
        PrevState st = prev.remove(id);

        double targetScale = (st != null) ? st.scale : 1.0;
        double targetMax = (st != null) ? st.maxHealth : 20.0;

        AttributeInstance s = player.getAttribute(Attribute.SCALE);
        if (s != null) s.setBaseValue(targetScale);

        AttributeInstance mh = player.getAttribute(Attribute.MAX_HEALTH);
        if (mh != null) mh.setBaseValue(targetMax);

        if (player.getHealth() > targetMax) player.setHealth(targetMax);

        player.removePotionEffect(PotionEffectType.SPEED);
        player.removePotionEffect(PotionEffectType.HASTE);
    }
}