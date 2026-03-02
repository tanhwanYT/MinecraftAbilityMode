package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class StoneAbility implements Ability {

    private final JavaPlugin plugin;

    // ===== 밸런스 설정 =====
    private static final int ZONE_RADIUS = 1;          // 3x3 => 반경 1
    private static final int GAIN_SECONDS = 60;        // 1분마다
    private static final int DECAY_SECONDS = 10;       // 10초마다 감소
    private static final int MAX_BONUS_HEARTS = 5;     // 최대 +5하트 (원하면 조절)

    // 1하트 = 2 health
    private static final double HEALTH_PER_HEART = 2.0;

    // ===== 상태 =====
    private static class Anchor {
        final UUID worldId;
        final int bx, bz;

        Anchor(World w, int bx, int bz) {
            this.worldId = w.getUID();
            this.bx = bx;
            this.bz = bz;
        }
    }

    // 플레이어별: 기준점 / 보너스 하트 / 다음 증가 시간 / 다음 감소 시간 / 현재 캠핑 중 여부
    private final Map<UUID, Anchor> anchors = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> bonusHearts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> nextGainAtMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> nextDecayAtMs = new ConcurrentHashMap<>();
    private final Set<UUID> decaying = ConcurrentHashMap.newKeySet();

    private BukkitRunnable tickTask;

    public StoneAbility(JavaPlugin plugin) {
        this.plugin = plugin;
        startTick();
    }

    @Override public String id() { return "stone"; }
    @Override public String name() { return "돌"; }
    @Override public int cooldownSeconds() { return 0; } // 패시브형

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("돌 : 같은 자리(3x3)에서 버티면 1분마다 최대체력 +1하트. 벗어나면 서서히 감소.");
        // 처음 부여 시: 현재 위치를 기준점으로 시작 (원하면 anchors.put 안 하고 “첫 정착 시점에만” 잡도록 바꿀 수도 있음)
        setNewAnchor(player);
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        // 패시브 능력이라 우클릭 발동은 없음(인터페이스 요구로만 구현)
        player.sendMessage("§7[돌] §f패시브 능력입니다. 같은 자리(3x3)에 서 있으면 자동으로 강화됩니다.");
        return false; // 쿨타임 소비/발동 처리 안 하려면 false
    }

    /**
     * 3x3 범위 벗어나면 풀리게: Move로 즉시 감지
     */
    @Override
    public void onMove(AbilitySystem system, PlayerMoveEvent event) {
        Player p = event.getPlayer();
        UUID id = p.getUniqueId();

        Anchor a = anchors.get(id);
        if (a == null) return;

        // 월드 다르면 즉시 풀림
        if (!p.getWorld().getUID().equals(a.worldId)) {
            breakCamp(p);
            return;
        }

        // X/Z 블록 기준 3x3 판정
        int bx = p.getLocation().getBlockX();
        int bz = p.getLocation().getBlockZ();

        if (Math.abs(bx - a.bx) > ZONE_RADIUS || Math.abs(bz - a.bz) > ZONE_RADIUS) {
            // 범위 이탈 -> 풀림 & 감소 시작
            breakCamp(p);
        }
    }

    // ===== 핵심 루프: 증가/감소 처리 =====

    private void startTick() {
        if (tickTask != null) return;

        tickTask = new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();

                for (Player p : Bukkit.getOnlinePlayers()) {
                    UUID id = p.getUniqueId();

                    // 이 능력 가진 플레이어만 처리하고 싶다면:
                    // - 네 AbilitySystem이 "이 플레이어의 능력이 뭔지" 알려주는 메서드가 있으면 여기서 필터하는 게 베스트
                    // 지금은 StoneAbility 내부 상태가 있는 플레이어만 처리
                    boolean hasState = anchors.containsKey(id) || bonusHearts.containsKey(id) || decaying.contains(id);
                    if (!hasState) continue;

                    // 1) 캠핑(정착) 중이면 1분마다 증가
                    if (!decaying.contains(id)) {
                        Anchor a = anchors.get(id);
                        if (a != null) {
                            long next = nextGainAtMs.getOrDefault(id, now + (GAIN_SECONDS * 1000L));
                            if (now >= next) {
                                int curBonus = bonusHearts.getOrDefault(id, 0);
                                if (curBonus < MAX_BONUS_HEARTS) {
                                    bonusHearts.put(id, curBonus + 1);
                                    applyMaxHealth(p);

                                    // 이펙트/피드백
                                    p.getWorld().playSound(p.getLocation(), Sound.BLOCK_STONE_PLACE, 0.8f, 1.0f);
                                    p.getWorld().spawnParticle(
                                            Particle.BLOCK,
                                            p.getLocation().add(0, 0.2, 0),
                                            18, 0.6, 0.1, 0.6, 0.0,
                                            Material.STONE.createBlockData()
                                    );
                                    p.sendActionBar("§7[돌] §f정착 보너스: §a+" + (curBonus + 1) + "하트");
                                } else {
                                    p.sendActionBar("§7[돌] §f정착 보너스: §a최대(" + MAX_BONUS_HEARTS + "하트)");
                                }

                                nextGainAtMs.put(id, now + (GAIN_SECONDS * 1000L));
                            }
                        } else {
                            // 기준점이 없다면 현재 위치에 새로 정착 시작
                            setNewAnchor(p);
                        }
                    }

                    // 2) 감소 중이면 10초마다 보너스 감소
                    if (decaying.contains(id)) {
                        int curBonus = bonusHearts.getOrDefault(id, 0);
                        if (curBonus <= 0) {
                            // 다 감소하면 종료
                            decaying.remove(id);
                            bonusHearts.put(id, 0);
                            p.sendActionBar("§7[돌] §f보너스가 사라졌습니다.");
                            // 다시 정착 가능하게 기준점 재설정
                            setNewAnchor(p);
                            continue;
                        }

                        long nextD = nextDecayAtMs.getOrDefault(id, now + (DECAY_SECONDS * 1000L));
                        if (now >= nextD) {
                            bonusHearts.put(id, curBonus - 1);
                            applyMaxHealth(p);

                            p.getWorld().spawnParticle(Particle.SMOKE, p.getLocation().add(0, 1.0, 0), 10, 0.3, 0.4, 0.3, 0.01);
                            p.sendActionBar("§7[돌] §f감소 중... §c-" + 1 + "하트 (§f남은 보너스: " + (curBonus - 1) + "하트§7)");
                            nextDecayAtMs.put(id, now + (DECAY_SECONDS * 1000L));
                        }
                    }
                }
            }
        };

        tickTask.runTaskTimer(plugin, 0L, 10L); // 0.5초마다 한번 체크
    }

    // ===== 도우미 =====

    private void setNewAnchor(Player p) {
        UUID id = p.getUniqueId();
        anchors.put(id, new Anchor(p.getWorld(), p.getLocation().getBlockX(), p.getLocation().getBlockZ()));
        nextGainAtMs.put(id, System.currentTimeMillis() + (GAIN_SECONDS * 1000L));
        // 감소 중이면 취소
        decaying.remove(id);
        nextDecayAtMs.remove(id);

        p.sendActionBar("§7[돌] §f정착 시작! §8(3x3 유지)");
    }

    private void breakCamp(Player p) {
        UUID id = p.getUniqueId();

        // 기준점 풀림
        anchors.remove(id);
        nextGainAtMs.remove(id);

        // 보너스가 있으면 감소 시작
        int curBonus = bonusHearts.getOrDefault(id, 0);
        if (curBonus > 0) {
            decaying.add(id);
            nextDecayAtMs.put(id, System.currentTimeMillis() + (DECAY_SECONDS * 1000L));

            p.getWorld().playSound(p.getLocation(), Sound.BLOCK_STONE_BREAK, 0.8f, 0.8f);
            p.sendActionBar("§7[돌] §f자리를 벗어났습니다. §c보너스가 서서히 감소합니다.");
        } else {
            // 보너스 없으면 그냥 즉시 새 정착 지점 설정 가능
            setNewAnchor(p);
        }
    }

    private void applyMaxHealth(Player p) {
        AttributeInstance attr = p.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) return;

        // “기본 최대체력”을 따로 저장하지 않고, 현재 base에서 보너스만 반영하는 단순 방식은 위험함.
        // 그래서: 플레이어가 가진 "원래 base"를 한 번 저장해두고 그 위에 보너스를 더하는 방식이 안전함.
        // 여기선 간단하게: 최초 base를 bonusHearts 맵과 함께 따로 저장하는 방식으로 구현.
        // -> 아래는 안전을 위해 base 저장을 추가로 함.
        double base = getOrInitBaseMaxHealth(p);
        int bonus = bonusHearts.getOrDefault(p.getUniqueId(), 0);

        double newMax = base + (bonus * HEALTH_PER_HEART);
        attr.setBaseValue(newMax);

        // 현재 체력이 최대체력보다 크면 깎기
        if (p.getHealth() > newMax) {
            p.setHealth(newMax);
        }
        // 증가했을 때 체감 좋게 약간 회복(원하면 제거 가능)
        else if (p.getHealth() < newMax && bonus > 0 && !decaying.contains(p.getUniqueId())) {
            p.setHealth(Math.min(newMax, p.getHealth() + 1.0)); // 반칸 정도만
        }
    }

    // ===== base max health 저장(필수 안전장치) =====
    private final Map<UUID, Double> baseMaxHealth = new ConcurrentHashMap<>();

    private double getOrInitBaseMaxHealth(Player p) {
        UUID id = p.getUniqueId();
        return baseMaxHealth.computeIfAbsent(id, _ -> {
            AttributeInstance attr = p.getAttribute(Attribute.MAX_HEALTH);
            return (attr != null) ? attr.getBaseValue() : 20.0;
        });
    }
}