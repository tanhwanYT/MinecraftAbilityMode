package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

public class JokerAbility implements Ability {

    @Override
    public String id() { return "joker"; }

    @Override
    public String name() { return "조커"; }

    @Override
    public int cooldownSeconds() { return 0; } // 원하는대로

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("조커 : 패시브로 미러링 능력(자신을 죽인 플레이어 같이 죽이기)이 있습니다.");
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        player.sendMessage("§e[조커] 패시브 능력입니다.");
        return false;
    }

    @Override
    public void onDamage(AbilitySystem system, EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player joker)) return;

        // 조커가 "플레이어"에게 맞는 상황만
        if (!(event instanceof EntityDamageByEntityEvent ede)) return;
        if (!(ede.getDamager() instanceof Player killer)) return;

        // 이 피해가 치명타(죽는 피해)인지 확인
        double finalDamage = event.getFinalDamage();
        double health = joker.getHealth();

        if (finalDamage < health) return; // 아직 안 죽음

        // ✅ 조커가 죽을 때(최종타가 플레이어) 그 킬러도 같이 죽이기
        // 같은 틱에 죽이면 로그/이벤트가 꼬일 수 있어서 1틱 뒤에 처리
        Bukkit.getScheduler().runTask(system.getPlugin(), () -> {
            if (!killer.isOnline()) return;
            if (killer.isDead()) return;
            killer.setHealth(0.0);
            killer.sendMessage("§c[조커] 조커를 죽여서 같이 죽었습니다!");
        });
    }
}
