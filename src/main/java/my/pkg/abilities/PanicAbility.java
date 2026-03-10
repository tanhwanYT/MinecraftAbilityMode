package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.GameMode;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class PanicAbility implements Ability {

    // 설정
    private static final double RANGE = 15.0;     // 가장 가까운 플레이어 탐색 범위
    private static final int NAUSEA_TICKS = 20 * 3; // 3초
    private static final int BLIND_TICKS = 20 * 6;  // 6초

    @Override
    public String id() { return "panic"; }

    @Override
    public String name() { return "패닉"; }

    @Override
    public int cooldownSeconds() { return 23; }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("패닉 : 우클릭 시 가장 가까운 플레이어와 위치가 바뀝니다.");
        player.sendMessage("대상과 본인에게 혼란/실명/인벤토리 룰렛 디버프를 가합니다.");
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

        shuffleInventory(player);
        shuffleInventory(target);

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

            if (p.getGameMode() == GameMode.SPECTATOR) continue;

            double d2 = p.getLocation().distanceSquared(self.getLocation());
            if (d2 <= bestDist2) {
                bestDist2 = d2;
                best = p;
            }
        }
        return best;
    }

    private void shuffleInventory(Player p) {
        PlayerInventory inv = p.getInventory();

        // 섞을 슬롯: 0~35 (핫바+메인). 36~40은 갑옷, 40은 오프핸드(버전에 따라 다름)라서 제외
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i <= 35; i++) slots.add(i);

        // 아이템들을 뽑아서 리스트로 만들고 셔플
        List<ItemStack> items = new ArrayList<>(slots.size());
        for (int slot : slots) {
            items.add(inv.getItem(slot));
        }

        Collections.shuffle(items, ThreadLocalRandom.current());

        // 다시 꽂기
        for (int i = 0; i < slots.size(); i++) {
            inv.setItem(slots.get(i), items.get(i));
        }

        // 클라 동기화
        p.updateInventory();

        // 피드백(선택)
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1.7f);
    }
}