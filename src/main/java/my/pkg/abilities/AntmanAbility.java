package my.pkg.abilities;

import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import my.pkg.AbilitySystem;

public class AntmanAbility implements Ability {

    @Override
    public String id() { return "antman"; }

    @Override
    public String name() { return "앤트맨"; }

    @Override
    public int cooldownSeconds() { return 25; }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("앤트맨 :본인의 크기를 랜덤으로 조절합니다. 크기가 커지면 최대체력이 증가하고, 크기가 작아지면 공격속도가 빨라집니다.");
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        double scale = ThreadLocalRandom.current().nextDouble(0.4, 1.61);

        AttributeInstance scaleAttr = player.getAttribute(Attribute.SCALE);
        if (scaleAttr != null) scaleAttr.setBaseValue(scale);

        AttributeInstance healthAttr = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = player.getMaxHealth();
        if (healthAttr != null) {
            maxHealth = Math.max(4.0, 20.0 * scale);
            healthAttr.setBaseValue(maxHealth);
            if (player.getHealth() > maxHealth) player.setHealth(maxHealth);
        }
        player.removePotionEffect(PotionEffectType.HASTE);

        if (scale < 1.0) {
            // 0.8~1.0: Haste 1, 0.6~0.8: Haste 2, 0.4~0.6: Haste 3 느낌
            int amp;
            if (scale < 0.6) amp = 2;      // Haste 3
            else if (scale < 0.8) amp = 1; // Haste 2
            else amp = 0;                  // Haste 1

            // 지속시간: 쿨타임보다 조금 길게(다음 사용 전까지 유지 느낌)
            player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE,
                    30 * 20, amp, true, false, true));
        }
        player.sendMessage(String.format("§a[앤트맨] 크기: %.2f / 최대체력: %.0f", scale, maxHealth));
        return true;
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        // 능력 수거시 크기와 체력 복구
        player.getAttribute(Attribute.SCALE).setBaseValue(1.0);
        player.getAttribute(Attribute.MAX_HEALTH).setBaseValue(20.0);

        if (player.getHealth() > 20.0) {
            player.setHealth(20.0);
        }
        player.removePotionEffect(PotionEffectType.HASTE);
    }

    private static AttributeInstance getAttributeInstance(Player player, String attributeField) {
        Attribute attribute = getAttributeByField(attributeField);
        if (attribute == null) return null;
        return player.getAttribute(attribute);
    }

    private static Attribute getAttributeByField(String attributeField) {
        try {
            return (Attribute) Attribute.class.getField(attributeField).get(null);
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
            return null;
        }
    }
}




