package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;
import org.bukkit.Bukkit;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SlotMachineAbility implements Ability {

    private final JavaPlugin plugin;

    // 쿨/중복 방지
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Double> bonusHealth = new ConcurrentHashMap<>();

    public SlotMachineAbility(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "slotmachine"; }
    @Override public String name() { return "슬롯머신"; }
    @Override public int cooldownSeconds() { return 30; }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("슬롯머신 : 머리위에 슬롯머신이 돌아갑니다. 버프 종류, 버프 레벨, 버프 시간을 정해서 부여합니다.");
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        if (running.contains(player.getUniqueId())) {
            player.sendMessage("§c[슬롯] 이미 돌아가는 중!");
            return false;
        }
        running.add(player.getUniqueId());
        startSlot(player);
        return true;
    }

    // ====== 데이터 테이블(예시) ======
    // 버프 종류(좋은/나쁜 섞기)
    private enum EffectKind {
        STRENGTH(true, PotionEffectType.STRENGTH),
        SPEED(true, PotionEffectType.SPEED),
        REGEN(true, PotionEffectType.REGENERATION),

        WEAKNESS(false, PotionEffectType.WEAKNESS),
        SLOWNESS(false, PotionEffectType.SLOWNESS),
        BLINDNESS(false, PotionEffectType.BLINDNESS),
        POISON(false, PotionEffectType.POISON);

        final boolean good;
        final PotionEffectType type;

        EffectKind(boolean good, PotionEffectType type) {
            this.good = good;
            this.type = type;
        }
    }

    // 강도(앰프) / 시간(초) 후보
    // Lv1 40% / Lv2 30% / Lv3 30%  -> amp(0,1,2)
    private int rollAmp(Random r) {
        double x = r.nextDouble();
        if (x < 0.40) return 0;      // Lv1
        if (x < 0.70) return 1;      // Lv2
        return 2;                    // Lv3
    }    // 1~3레벨
    private static final int[] DURATIONS = {3, 4, 5, 6, 7};

    // 확률 가중치(좋은 60 / 나쁜 40 느낌)
    private EffectKind rollKind(Random r) {
        boolean wantGood = r.nextDouble() < 0.50;
        List<EffectKind> pool = new ArrayList<>();
        for (EffectKind k : EffectKind.values()) {
            if (k.good == wantGood) pool.add(k);
        }
        return pool.get(r.nextInt(pool.size()));
    }

    // ====== 슬롯 세션 ======
    private void startSlot(Player p) {
        World w = p.getWorld();
        Random r = new Random();

        // 3개 릴 텍스트디스플레이 생성
        TextDisplay reelKind = spawnReel(p, 0.35f, "§e[버프종류]");
        TextDisplay reelAmp  = spawnReel(p, 0.15f, "§b[강도]");
        TextDisplay reelDur  = spawnReel(p, -0.05f, "§a[시간]");

        // 시각 효과(소리)
        w.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.4f);

        new BukkitRunnable() {
            // 도는 중 후보값
            EffectKind curKind = EffectKind.SPEED;
            int curAmp = 0;
            int curDur = 10;

            // 멈춘 결과
            EffectKind finalKind;
            int finalAmp;
            int finalDur;

            int tick = 0;

            final int jackpot;
            {
                double j = r.nextDouble();
                if (j < 0.01) jackpot = 666;
                else if (j < 0.02) jackpot = 777;
                else jackpot = 0;
            }

            // 슬롯 느낌: 점점 느려지며 1개씩 멈추기
            boolean stopKind = false;
            boolean stopAmp = false;
            boolean stopDur = false;

            int interval = 2;     // 초기 회전 속도(틱)
            int nextRollAt = 0;   // 다음 변경 시점

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead()) {
                    cleanup(p, reelKind, reelAmp, reelDur);
                    cancel();
                    return;
                }

                // 머리 위 따라다니게(매틱 텔레포트)
                followHead(p, reelKind, 0.35);
                followHead(p, reelAmp, 0.15);
                followHead(p, reelDur, -0.05);

                // 일정 틱마다 값 변경(회전)
                if (tick >= nextRollAt) {
                    if (!stopKind) curKind = rollKind(r);
                    if (!stopAmp)  curAmp  = rollAmp(r);
                    if (!stopDur)  curDur  = DURATIONS[r.nextInt(DURATIONS.length)];

                    if (jackpot == 666) {
                        reelKind.setText("§4§l[ 6 ]");
                        reelAmp.setText("§4§l[ 6 ]");
                        reelDur.setText("§4§l[ 6 ]");
                    } else if (jackpot == 777) {
                        reelKind.setText("§6§l[ 7 ]");
                        reelAmp.setText("§6§l[ 7 ]");
                        reelDur.setText("§6§l[ 7 ]");
                    } else {
                        reelKind.setText("§e버프: §f" + prettyKind(curKind));
                        reelAmp.setText("§b강도: §fLv." + (curAmp + 1));
                        reelDur.setText("§a시간: §f" + curDur + "s");
                    }

                    // 딸깍 소리
                    p.getWorld().playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.8f);

                    nextRollAt = tick + interval;
                }

                // 멈추는 타이밍 설계 (총 연출 약 2.5~3초)
                // 1) 20틱(1초)부터 점점 느려짐
                if (tick == 20) interval = 3;
                if (tick == 30) interval = 4;

                // 2) 40틱쯤 첫 릴(버프종류) 멈춤
                if (tick == 40 && !stopKind) {
                    stopKind = true;
                    finalKind = curKind;
                    flash(reelKind);
                    p.getWorld().playSound(p.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.6f, 1.2f);
                }

                // 3) 50틱쯤 둘째 릴(강도) 멈춤
                if (tick == 50 && !stopAmp) {
                    stopAmp = true;
                    finalAmp = curAmp;
                    flash(reelAmp);
                    p.getWorld().playSound(p.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.6f, 1.3f);
                }

                // 4) 60틱쯤 셋째 릴(시간) 멈춤 + 결과 적용
                if (tick == 60 && !stopDur) {
                    stopDur = true;
                    finalDur = curDur;
                    flash(reelDur);
                    p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.6f);

                    // 최종 값이 아직 null일 수 있으니(안전)
                    if (!stopKind) finalKind = curKind;
                    if (!stopAmp)  finalAmp = curAmp;
                    if (!stopDur)  finalDur = curDur;

                    if (jackpot == 666) {
                        doJackpot666(p);
                    } else if (jackpot == 777) {
                        doJackpot777(p);
                    } else {
                        applyResult(p, curKind, curAmp, curDur);
                    }

                    // 1초 후 디스플레이 정리
                    new BukkitRunnable() {
                        @Override public void run() {
                            cleanup(p, reelKind, reelAmp, reelDur);
                        }
                    }.runTaskLater(plugin, 20L);

                    cancel();
                    return;
                }

                tick++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private String prettyKind(EffectKind k) {
        return switch (k) {
            case STRENGTH -> "힘";
            case SPEED -> "속도";
            case REGEN -> "재생";
            case WEAKNESS -> "나약함";
            case SLOWNESS -> "구속";
            case BLINDNESS -> "실명";
            case POISON -> "독";
        };
    }

    private void doJackpot666(Player p) {
        World w = p.getWorld();
        Location loc = p.getLocation();

        Bukkit.broadcastMessage("§4§l[슬롯] §c" + p.getName() + "§f님이 §4§l666 저주§f에 당첨되었습니다!");
        w.playSound(loc, Sound.ENTITY_WITHER_SPAWN, 1.0f, 0.6f);
        w.playSound(loc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.9f, 0.8f);

        // 연출: 번개 + 연기 + 소울 파티클
        w.strikeLightningEffect(loc);
        w.spawnParticle(Particle.SOUL, loc.add(0, 1.0, 0), 80, 0.6, 0.8, 0.6, 0.02);
        w.spawnParticle(Particle.SMOKE, loc.add(0, 1.0, 0), 120, 0.6, 0.9, 0.6, 0.01);

        // 즉사
        p.setHealth(0.0);
    }

    private void doJackpot777(Player p) {
        World w = p.getWorld();
        Location loc = p.getLocation();

        Bukkit.broadcastMessage("§6§l[슬롯] §e" + p.getName() + "§f님이 §6§l777 대박§f에 당첨되었습니다!");
        w.playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.8f);
        w.playSound(loc, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.2f);

        // 연출: 불꽃/엔드로드/해피빌저
        w.spawnParticle(Particle.FIREWORK, loc.add(0, 1.0, 0), 120, 0.8, 1.0, 0.8, 0.08);
        w.spawnParticle(Particle.END_ROD, loc.add(0, 1.2, 0), 80, 0.6, 0.8, 0.6, 0.02);
        w.spawnParticle(Particle.HAPPY_VILLAGER, loc.add(0, 1.0, 0), 40, 0.6, 0.6, 0.6, 0.01);

        // 최대체력 +2줄 = +4 하트 = +8 health
        AttributeInstance attr = p.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) {
            double gain = 8.0; // +4하트
            double before = attr.getBaseValue();
            double after = before + gain;
            attr.setBaseValue(after);

            // 777 보너스 누적 기록
            UUID id = p.getUniqueId();
            bonusHealth.put(id, bonusHealth.getOrDefault(id, 0.0) + gain);

            // 체력도 같이 채워주기
            p.setHealth(Math.min(after, p.getHealth() + gain));
        }
    }

    private void applyResult(Player p, EffectKind kind, int amp, int durSeconds) {
        int ticks = durSeconds * 20;

        // 결과 메시지(주변 가시성)
        String msg = "§d[슬롯] §f" + prettyKind(kind) + " §7Lv." + (amp + 1) + " §f" + durSeconds + "초!";
        p.sendMessage(msg);
        p.getWorld().spawnParticle(Particle.FIREWORK, p.getLocation().add(0, 1.0, 0), 40, 0.4, 0.6, 0.4, 0.05);

        // 버프/디버프 적용
        p.addPotionEffect(new org.bukkit.potion.PotionEffect(kind.type, ticks, amp, true, true, true));

        // 나쁜 효과면 소리/이펙트 조금 다르게
        if (!kind.good) {
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WITCH_CELEBRATE, 0.8f, 0.9f);
            p.getWorld().spawnParticle(Particle.SMOKE, p.getLocation().add(0, 1.0, 0), 25, 0.4, 0.6, 0.4, 0.01);
        }
    }

    // ====== Display 유틸 ======

    private TextDisplay spawnReel(Player p, float yOffset, String initialText) {
        Location loc = p.getLocation().add(0, 2.25 + yOffset, 0);

        TextDisplay td = p.getWorld().spawn(loc, TextDisplay.class, e -> {
            e.setText(initialText);
            e.setBillboard(Display.Billboard.CENTER); // 항상 플레이어를 향해
            e.setSeeThrough(true);
            e.setShadowed(true);
            e.setDefaultBackground(false);

            // 살짝 크게
            Transformation t = e.getTransformation();
            e.setTransformation(new Transformation(
                    t.getTranslation(),
                    t.getLeftRotation(),
                    new Vector3f(1.15f, 1.15f, 1.15f),
                    t.getRightRotation()
            ));
        });

        return td;
    }

    private void followHead(Player p, TextDisplay td, double yOffset) {
        if (td == null || td.isDead()) return;
        Location loc = p.getLocation().add(0, 2.25 + yOffset, 0);
        // yaw/pitch는 크게 상관 없지만 깔끔하게 0으로
        loc.setYaw(0);
        loc.setPitch(0);
        td.teleport(loc);
    }

    private void flash(TextDisplay td) {
        // “멈췄다!” 느낌: 텍스트 강조 + 파티클 살짝
        String t = td.getText();
        td.setText("§f§l" + t.replace("§f", "")); // 아주 간단 강조
        World w = td.getWorld();
        w.spawnParticle(Particle.CRIT, td.getLocation(), 15, 0.15, 0.15, 0.15, 0.02);
    }

    private void cleanup(Player p, TextDisplay a, TextDisplay b, TextDisplay c) {
        if (a != null && !a.isDead()) a.remove();
        if (b != null && !b.isDead()) b.remove();
        if (c != null && !c.isDead()) c.remove();
        running.remove(p.getUniqueId());
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        UUID id = player.getUniqueId();
        double added = bonusHealth.getOrDefault(id, 0.0);
        if (added <= 0.0) return;

        AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) {
            double base = attr.getBaseValue();
            double newBase = Math.max(1.0, base - added);
            attr.setBaseValue(newBase);

            // 현재 체력이 최대체력보다 높아지면 맞춰줌
            if (player.getHealth() > newBase) {
                player.setHealth(newBase);
            }
        }

        bonusHealth.remove(id);
    }
}