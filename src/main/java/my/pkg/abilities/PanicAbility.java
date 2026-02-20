package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class PanicAbility implements Ability {

    // 설정
    private static final double RANGE = 12.0;     // 가장 가까운 플레이어 탐색 범위
    private static final int NAUSEA_TICKS = 20 * 3; // 3초
    private static final int BLIND_TICKS = 20 * 1;  // 1초

    @Override
    public String id() { return "panic"; }

    @Override
    public String name() { return "패닉"; }

    @Override
    public int cooldownSeconds() { return 18; }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("패닉 : 우클릭 시 가장 가까운 플레이어와 위치가 바뀝니다.");
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        Player target = findNearestPlayer(player, RANGE);

        if (target == null) {
            player.sendMessage("§7[패닉] §f주변에 교체할 플레이어가 없습니다.");
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 0.8f);
            return false; // 실패 시 쿨타임 소비 X
        }

        Location a = player.getLocation().clone();
        Location b = target.getLocation().clone();

        // 위치 교체
        player.teleport(b);
        target.teleport(a);

        // 시야 흔들림 + 잠깐 실명
        applyPanicDebuff(player);
        applyPanicDebuff(target);

        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.2f);
        target.playSound(target.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.2f);

        player.sendMessage("§d[패닉] §f" + target.getName() + "와 위치를 바꿨다!");
        target.sendMessage("§d[패닉] §f" + player.getName() + "와 위치가 바뀌었다!");

        return true;
    }

    private void applyPanicDebuff(Player p) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, NAUSEA_TICKS, 0, true, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, BLIND_TICKS, 0, true, false, true));
    }

    private Player findNearestPlayer(Player self, double range) {
        Player best = null;
        double bestDist2 = range * range;

        for (Player p : self.getWorld().getPlayers()) {
            if (p.equals(self)) continue;
            if (!p.isOnline() || p.isDead()) continue;

            double d2 = p.getLocation().distanceSquared(self.getLocation());
            if (d2 <= bestDist2) {
                bestDist2 = d2;
                best = p;
            }
        }
        return best;
    }
}