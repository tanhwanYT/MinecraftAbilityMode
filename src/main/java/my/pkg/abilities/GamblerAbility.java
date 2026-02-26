package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.potion.PotionEffectType;

import java.util.concurrent.ThreadLocalRandom;

public class GamblerAbility implements Ability {

    private NamespacedKey hpKey;
    private static final double BONUS_HP = 4.0;

    @Override
    public String id() { return "gambler"; }

    @Override
    public String name() { return "도박꾼"; }

    @Override
    public int cooldownSeconds() { return 0; } // 패시브라 의미 없음

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        player.sendMessage("§d[도박꾼] 패시브 능력입니다.");
        return false;
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        removeHpBonus(player);
    }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        // 사용법 안내
        player.sendMessage("도박꾼 : '맨손' 공격 시 70% 확률로 상대에게 3~7의 랜덤 고정피해를 입힙니다. 하지만 30% 확률로 본인에게 대미지가 들어갈수있습니다.");
        if (hpKey == null) hpKey = new NamespacedKey(system.getPlugin(), "gambler_hp_bonus");
        applyHpBonus(player);
    }

    @Override
    public void onAttack(AbilitySystem system, EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;

        // 맨손만
        if (attacker.getInventory().getItemInMainHand().getType() != Material.AIR) return;

        int dmg = ThreadLocalRandom.current().nextInt(3, 7); // 3~7
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
    private void applyHpBonus(Player p) {
        if (hpKey == null) return;

        var attr = p.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) return;

        // 기존 모디파이어 제거(중복 방지)
        attr.getModifiers().stream()
                .filter(m -> hpKey.equals(m.getKey()))
                .forEach(attr::removeModifier);

        attr.addModifier(new AttributeModifier(
                hpKey,
                BONUS_HP,
                AttributeModifier.Operation.ADD_NUMBER
        ));

        // 현재 체력이 최대체력보다 크면 보정
        double max = p.getMaxHealth();
        if (p.getHealth() > max) p.setHealth(max);
    }

    private void removeHpBonus(Player p) {
        if (hpKey == null) return;

        var attr = p.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) return;

        attr.getModifiers().stream()
                .filter(m -> hpKey.equals(m.getKey()))
                .forEach(attr::removeModifier);

        double max = p.getMaxHealth();
        if (p.getHealth() > max) p.setHealth(max);
    }
}
