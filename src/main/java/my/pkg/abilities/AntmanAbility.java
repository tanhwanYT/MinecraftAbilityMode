package my.pkg.abilities;

import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

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
        player.sendMessage("앤트맨 :본인의 크기를 랜덤으로 조절합니다. 최대 체력도 크기에 비례하여 변경됩니다.");
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




