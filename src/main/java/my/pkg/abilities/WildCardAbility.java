package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class WildCardAbility implements Ability {

    private static final List<String> POOL = List.of(
            "malphite",
            "viper",
            "kiyathow",
            "antman",
            "sniper",
            "donation",
            "panic",
            "glow",
            "chainarm",
            "shadowstep",
            "taliyah",
            "backattacker"
    );

    private final Map<UUID, String> lastPicked = new HashMap<>();

    @Override
    public String id() {
        return "wildcard";
    }

    @Override
    public String name() {
        return "와일드카드";
    }

    @Override
    public int cooldownSeconds() {
        // 와일드카드 자체 고정 쿨타임
        return 25;
    }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("와일드카드 : 네더 스타 우클릭 시 액티브 능력 중 하나가 랜덤으로 발동됩니다");
        player.sendMessage("-말파이트, 바이퍼, 끼얏호우, 앤트맨, 스나이퍼, 도네이션, 패닉, 라이징스타, 사슬팔, 섀도우스탭, 탈리아, 백어택커-");
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        UUID uuid = player.getUniqueId();
        String prev = lastPicked.get(uuid);

        List<Ability> candidates = new ArrayList<>();

        for (String key : POOL) {
            Ability ability = system.getRegisteredAbility(key);
            if (ability == null) continue;

            // 자기 자신 제외
            if (ability == this) continue;

            // 같은 능력 연속 방지
            if (prev != null && key.equalsIgnoreCase(prev)) continue;

            candidates.add(ability);
        }

        // 전부 걸러졌으면 연속 방지 무시
        if (candidates.isEmpty()) {
            for (String key : POOL) {
                Ability ability = system.getRegisteredAbility(key);
                if (ability == null) continue;
                if (ability == this) continue;
                candidates.add(ability);
            }
        }

        if (candidates.isEmpty()) {
            player.sendMessage("§c[와일드카드] 사용 가능한 후보 능력이 없습니다.");
            return false;
        }

        Ability picked = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));

        String pickedKey = system.getAbilityKey(picked);
        if (pickedKey != null) {
            lastPicked.put(uuid, pickedKey.toLowerCase());
        }

        player.sendMessage("§d[와일드카드] §f이번 스킬: §e" + picked.name());
        player.sendActionBar("§d와일드카드 → §e" + picked.name());
        player.playSound(player.getLocation(), Sound.UI_TOAST_IN, 1.0f, 1.2f);

        try {
            boolean activated = picked.activate(system, player);

            if (!activated) {
                player.sendMessage("§c[와일드카드] 뽑힌 능력이 지금은 발동되지 않았습니다.");
                return false;
            }

            return true;
        } catch (Exception e) {
            player.sendMessage("§c[와일드카드] 랜덤 스킬 발동 중 오류가 발생했습니다: " + picked.name());
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        UUID uuid = player.getUniqueId();
        lastPicked.remove(uuid);
    }
}