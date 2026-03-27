package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;

public class BangBangAbility implements Ability {

    private static final double BANGBANG_MAX_HEALTH = 16.0; // 8칸

    @Override
    public String id() {
        return "bangbang";
    }

    @Override
    public String name() {
        return "방방";
    }

    @Override
    public int cooldownSeconds() {
        return 0;
    }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) {
            attr.setBaseValue(BANGBANG_MAX_HEALTH);
            if (player.getHealth() > BANGBANG_MAX_HEALTH) {
                player.setHealth(BANGBANG_MAX_HEALTH);
            }
        }

        player.sendMessage("방방 : 공중에 있을 때 피해를 받지 않습니다. 최대 체력은 8칸입니다.");
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) {
            attr.setBaseValue(20.0); // 기본 10칸
        }

        if (player.getHealth() > 20.0) {
            player.setHealth(20.0);
        }
    }

    @Override
    public void onDamage(AbilitySystem system, EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;

        if (!isAirborne(player)) return;

        event.setCancelled(true);
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        return false;
    }

    private boolean isAirborne(Player player) {
        if (player.isOnGround()) return false;
        if (player.isInWater()) return false;
        if (player.isClimbing()) return false;
        if (player.isGliding()) return false;
        return true;
    }
}