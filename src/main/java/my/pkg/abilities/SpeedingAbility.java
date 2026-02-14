package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class SpeedingAbility implements Ability {

    @Override
    public String id() { return "speeding"; }

    @Override
    public String name() { return "속도위반"; }

    @Override
    public int cooldownSeconds() { return 0; } // 패시브라 의미 없음

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        // 패시브: 네더스타 발동 안 씀
        player.sendMessage("§e[속도위반] 패시브 능력입니다. 노란 양털을 밟아보세요!");
        return false;
    }

    @Override
    public void onMove(AbilitySystem system, PlayerMoveEvent event) {
        Player p = event.getPlayer();

        // 발밑 블록 체크 (to 기준)
        Material under = event.getTo().getBlock().getRelative(0, -1, 0).getType();
        if (under == Material.YELLOW_WOOL) {
            // 2초짜리 버프를 계속 갱신 (움직이는 동안 유지)
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 40, 1, false, false));
        }
    }
}
