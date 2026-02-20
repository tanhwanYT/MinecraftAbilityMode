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
        player.sendMessage("§e[속도위반] 패시브 능력입니다.");
        return false;
    }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        // 사용법 안내
        player.sendMessage("속도위반 : 노란양털을 밟았을때 속도버프를 받습니다. ");
    }

    @Override
    public void onMove(AbilitySystem system, PlayerMoveEvent event) {
        Player p = event.getPlayer();

        // 발밑 블록 체크 (to 기준)
        Material under = event.getTo().getBlock().getRelative(0, -1, 0).getType();
        if (under == Material.YELLOW_WOOL) {
            // 2초짜리 버프를 계속 갱신 (움직이는 동안 유지)
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20, 1, false, false));
        }
    }
}
