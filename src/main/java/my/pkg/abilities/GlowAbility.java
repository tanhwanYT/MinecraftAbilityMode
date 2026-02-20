package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class GlowAbility implements Ability {

    private static final int GLOW_FOREVER_TICKS = 20 * 60 * 60; // 1시간 (사실상 무한)
    private static final int TARGET_GLOW_TICKS = 20 * 6;        // 맞춘 상대 발광 6초

    @Override
    public String id() { return "glow"; }

    @Override
    public String name() { return "라이징스타"; }

    @Override
    public int cooldownSeconds() { return 0; } // 우클릭 액티브가 없어서 의미는 덜함(그래도 시스템상 필요하면 유지)

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("라이징스타 : 당신은 항상 빛납니다. 대신 공격한 상대도 잠깐 빛나게 합니다.");

        // 본인 상시 발광
        player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, GLOW_FOREVER_TICKS, 0, true, false, true));
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        player.removePotionEffect(PotionEffectType.GLOWING);
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        player.sendMessage("§7[라이징스타]§f 패시브 능력입니다.");
        return false;
    }

    @Override
    public void onAttack(AbilitySystem system, EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof org.bukkit.entity.LivingEntity victim)) return;

        victim.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, TARGET_GLOW_TICKS, 0, true, false, true));

        // 소리는 맞은 엔티티 위치에서
        victim.getWorld().playSound(victim.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.4f);

        attacker.sendActionBar("§e[라이징스타] §f대상이 빛납니다!");
    }
}