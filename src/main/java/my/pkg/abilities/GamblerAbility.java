package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.concurrent.ThreadLocalRandom;

public class GamblerAbility implements Ability {

    @Override
    public String id() { return "gambler"; }

    @Override
    public String name() { return "도박꾼"; }

    @Override
    public int cooldownSeconds() { return 0; } // 패시브라 의미 없음

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        player.sendMessage("§d[도박꾼] 패시브 능력입니다. 맨손으로 때려보세요!");
        return false;
    }

    @Override
    public void onAttack(AbilitySystem system, EntityDamageByEntityEvent event) {
        // 공격자
        if (!(event.getDamager() instanceof Player attacker)) return;

        // 타겟은 LivingEntity만 (플레이어/몹/동물)
        if (!(event.getEntity() instanceof LivingEntity target)) return;

        // ✅ 맨손 조건
        if (attacker.getInventory().getItemInMainHand().getType() != Material.AIR) return;

        // 원래 데미지 취소하고 우리가 "고정 피해" 적용
        event.setCancelled(true);

        int dmg = ThreadLocalRandom.current().nextInt(1, 9); // 1~8
        boolean backfire = ThreadLocalRandom.current().nextBoolean(); // 50%

        if (backfire) {
            attacker.damage(dmg);
            attacker.sendMessage("§c[도박꾼] 꽝! 내가 " + dmg + " 피해를 받았다!");
        } else {
            target.damage(dmg, attacker);
            attacker.sendMessage("§a[도박꾼] 적중! 상대에게 " + dmg + " 피해!");
        }
    }
}
