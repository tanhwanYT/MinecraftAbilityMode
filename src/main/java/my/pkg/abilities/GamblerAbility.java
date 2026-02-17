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
    public void onGrant(AbilitySystem system, Player player) {
        // 사용법 안내
        player.sendMessage("도박꾼 : 맨손으로 타격시 상대에게 1~5의 랜덤대미지를 입힙니다. 하지만 본인이 피해를 입을수도 있습니다.");
    }

    @Override
    public void onAttack(AbilitySystem system, EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;

        // 맨손만
        if (attacker.getInventory().getItemInMainHand().getType() != Material.AIR) return;

        int dmg = ThreadLocalRandom.current().nextInt(1, 9); // 1~8
        boolean backfire = ThreadLocalRandom.current().nextBoolean();

        if (backfire) {
            // ✅ 이번 공격은 "빗나감" 처리: 대상 피해 0으로
            event.setDamage(0.0);

            // ✅ 대신 본인이 피해를 받음 (다음 틱에 주는 게 안전)
            system.getPlugin().getServer().getScheduler().runTask(system.getPlugin(), () -> {
                if (attacker.isOnline() && !attacker.isDead()) {
                    attacker.damage(dmg);
                }
            });

            attacker.sendMessage("§c[도박꾼] 꽝! 내가 " + dmg + " 피해를 받았다!");
        } else {
            // ✅ 공격은 정상 처리되게 두고, 데미지만 고정값으로 덮어쓰기
            event.setDamage((double) dmg);
            attacker.sendMessage("§a[도박꾼] 적중! 상대에게 " + dmg + " 피해!");
        }
    }
}
