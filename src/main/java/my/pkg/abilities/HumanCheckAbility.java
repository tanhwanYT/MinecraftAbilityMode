package my.pkg.abilities;

import my.pkg.AbilitySystem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class HumanCheckAbility implements Ability, Listener {

    private final JavaPlugin plugin;

    private static final int COOLDOWN = 35;
    private static final int RANGE = 12;

    private int nextSessionId = 1;

    private final Map<Integer, AuthDuel> duels = new HashMap<>();
    private final Map<UUID, PlayerAuthState> states = new HashMap<>();

    public HumanCheckAbility(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "human_check";
    }

    @Override
    public String name() {
        return "본인인증";
    }

    @Override
    public int cooldownSeconds() {
        return COOLDOWN;
    }

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("§b[본인인증] §f상대를 바라보고 사용하면 인증 대결을 시작합니다.");
        player.sendMessage("§7먼저 인증을 끝내면 버프, 늦으면 디버프를 받습니다.");
    }

    @Override
    public void onRemove(AbilitySystem system, Player player) {
        forceLeave(player);
    }

    @Override
    public boolean activate(AbilitySystem system, Player caster) {
        if (states.containsKey(caster.getUniqueId())) {
            caster.sendMessage("§c[본인인증] 이미 인증 중입니다.");
            return false;
        }

        Entity targetEntity = caster.getTargetEntity(RANGE, false);
        if (!(targetEntity instanceof Player target)) {
            caster.sendMessage("§c[본인인증] 바라보는 플레이어가 없습니다.");
            return false;
        }

        if (target.equals(caster)) {
            caster.sendMessage("§c[본인인증] 자기 자신에게는 사용할 수 없습니다.");
            return false;
        }

        if (states.containsKey(target.getUniqueId())) {
            caster.sendMessage("§c[본인인증] 대상이 이미 인증 중입니다.");
            return false;
        }

        int sessionId = nextSessionId++;
        AuthDuel duel = new AuthDuel(sessionId, caster.getUniqueId(), target.getUniqueId());
        duels.put(sessionId, duel);

        PlayerAuthState casterState = new PlayerAuthState(sessionId, caster.getUniqueId());
        PlayerAuthState targetState = new PlayerAuthState(sessionId, target.getUniqueId());

        states.put(caster.getUniqueId(), casterState);
        states.put(target.getUniqueId(), targetState);

        caster.sendTitle("§b본인인증 시작", "§f상대보다 먼저 통과하세요", 5, 40, 10);
        target.sendTitle("§b본인인증 시작", "§f상대보다 먼저 통과하세요", 5, 40, 10);

        caster.playSound(caster.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.4f);
        target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.4f);

        sendCodeStep(caster, casterState);
        sendCodeStep(target, targetState);

        return true;
    }

    private void sendCodeStep(Player player, PlayerAuthState state) {
        state.stage = Stage.CODE;
        state.code = randomCode();
        state.input = "";

        player.sendMessage("");
        player.sendMessage("§b§l[본인인증]");
        player.sendMessage("§f인증코드를 입력하세요.");
        player.sendMessage("§7인증코드: §e§l" + state.code);
        player.sendMessage("§7현재 입력: §f" + displayInput(state.input));
        sendKeypad(player, state);
    }

    private void sendKeypad(Player player, PlayerAuthState state) {
        player.sendMessage(makeButtonLine(state.sessionId, "1", "2", "3"));
        player.sendMessage(makeButtonLine(state.sessionId, "4", "5", "6"));
        player.sendMessage(makeButtonLine(state.sessionId, "7", "8", "9"));

        Component zero = digitButton(state.sessionId, "0");
        Component clear = Component.text(" §c[지우기] ")
                .clickEvent(ClickEvent.runCommand("/auth_clear " + state.sessionId));
        Component submit = Component.text(" §a[확인] ")
                .clickEvent(ClickEvent.runCommand("/auth_submit " + state.sessionId));

        player.sendMessage(Component.text("     ").append(zero).append(clear).append(submit));
    }

    private Component makeButtonLine(int sessionId, String a, String b, String c) {
        return Component.text("")
                .append(digitButton(sessionId, a))
                .append(Component.text(" "))
                .append(digitButton(sessionId, b))
                .append(Component.text(" "))
                .append(digitButton(sessionId, c));
    }

    private Component digitButton(int sessionId, String digit) {
        return Component.text("§f[§e" + digit + "§f]")
                .clickEvent(ClickEvent.runCommand("/auth_digit " + sessionId + " " + digit));
    }

    private void sendRobotStep(Player player, PlayerAuthState state) {
        state.stage = Stage.ROBOT;
        state.noCount = 0;

        player.sendMessage("");
        player.sendMessage("§b§l[본인인증]");
        player.sendMessage("§f당신은 로봇입니까?");
        sendYesNo(player, state.sessionId, "§a[예]", "§c[아니오]");
    }

    private void sendRobotQuestion(Player player, PlayerAuthState state) {
        Question q = QUESTIONS[ThreadLocalRandom.current().nextInt(QUESTIONS.length)];

        state.correctAnswerYes = q.answerYes;

        player.sendMessage("");
        player.sendMessage("§b§l[본인인증]");
        player.sendMessage(q.text);

        sendYesNo(player, state.sessionId, "§a[예]", "§c[아니오]");
    }

    private static class Question {
        final String text;
        final boolean answerYes; // true = "예"가 정답

        Question(String text, boolean answerYes) {
            this.text = text;
            this.answerYes = answerYes;
        }
    }

    private static final Question[] QUESTIONS = {
            new Question("§f당신은 로봇입니까?", false),
            new Question("§f당신은 인간입니까?", true),
            new Question("§f이 질문에 '예'를 누르세요.", true),
            new Question("§f이 질문에 '아니오'를 누르세요.", false),
            new Question("§f당신은 자동화된 프로그램입니까?", false),
    };

    private void sendYesNo(Player player, int sessionId, String yesText, String noText) {
        Component yes = Component.text(yesText)
                .clickEvent(ClickEvent.runCommand("/auth_yes " + sessionId));
        Component no = Component.text(" " + noText)
                .clickEvent(ClickEvent.runCommand("/auth_no " + sessionId));

        player.sendMessage(yes.append(no));
    }

    @EventHandler
    public void onAuthCommand(PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage();
        if (!msg.startsWith("/auth_")) return;

        event.setCancelled(true);

        Player player = event.getPlayer();
        String[] args = msg.substring(1).split(" ");

        if (args.length < 2) return;

        String command = args[0];
        int sessionId;

        try {
            sessionId = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            return;
        }

        PlayerAuthState state = states.get(player.getUniqueId());
        if (state == null || state.sessionId != sessionId) {
            player.sendMessage("§c[본인인증] 유효하지 않은 인증입니다.");
            return;
        }

        switch (command) {
            case "auth_digit" -> {
                if (args.length < 3) return;
                handleDigit(player, state, args[2]);
            }
            case "auth_clear" -> {
                state.input = "";
                player.sendMessage("§7입력을 초기화했습니다.");
                player.sendMessage("§7현재 입력: §f" + displayInput(state.input));
                sendKeypad(player, state);
            }
            case "auth_submit" -> handleSubmit(player, state);
            case "auth_yes" -> handleYes(player, state);
            case "auth_no" -> handleNo(player, state);
        }
    }

    private void handleDigit(Player player, PlayerAuthState state, String digit) {
        if (state.stage != Stage.CODE) return;
        if (!digit.matches("[0-9]")) return;

        if (state.input.length() >= 4) {
            player.sendMessage("§c이미 4자리를 입력했습니다. 확인 또는 지우기를 누르세요.");
            return;
        }

        state.input += digit;
        player.sendMessage("§7현재 입력: §f" + displayInput(state.input));

        if (state.input.length() >= 4) {
            player.sendMessage("§a[확인] 버튼을 누르세요.");
        }
    }

    private void handleSubmit(Player player, PlayerAuthState state) {
        if (state.stage != Stage.CODE) return;

        if (state.input.equals(state.code)) {
            player.sendMessage("§a인증코드 확인 완료.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.8f);
            sendRobotStep(player, state);
        } else {
            player.sendMessage("§c인증번호가 틀렸습니다. 처음부터 다시 진행합니다.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.7f);
            sendCodeStep(player, state);
        }
    }

    private void handleYes(Player player, PlayerAuthState state) {
        if (state.stage != Stage.ROBOT) return;

        if (state.correctAnswerYes) {
            state.noCount++;
        } else {
            failAndReset(player, state);
            return;
        }

        nextOrComplete(player, state);
    }

    private void handleNo(Player player, PlayerAuthState state) {
        if (state.stage != Stage.ROBOT) return;

        if (!state.correctAnswerYes) {
            state.noCount++;
        } else {
            failAndReset(player, state);
            return;
        }

        nextOrComplete(player, state);
    }

    private void nextOrComplete(Player player, PlayerAuthState state) {
        if (state.noCount >= 4) {
            complete(player, state);
            return;
        }

        sendRobotQuestion(player, state);
    }

    private void failAndReset(Player player, PlayerAuthState state) {
        player.sendMessage("§c틀렸습니다! 처음부터 다시 인증합니다.");
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 0.8f);
        sendCodeStep(player, state);
    }

    private void complete(Player winner, PlayerAuthState winnerState) {
        AuthDuel duel = duels.get(winnerState.sessionId);
        if (duel == null || duel.finished) return;

        duel.finished = true;

        UUID loserId = duel.other(winner.getUniqueId());
        Player loser = Bukkit.getPlayer(loserId);

        states.remove(winner.getUniqueId());
        states.remove(loserId);
        duels.remove(winnerState.sessionId);

        winner.sendTitle("§a본인인증 성공!", "§f당신은 인간입니다", 5, 40, 10);
        winner.sendMessage("§a[본인인증] §f인증을 먼저 완료했습니다!");
        winner.playSound(winner.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);

        winner.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 8, 1, false, true));
        winner.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 20 * 8, 0, false, true));
        winner.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 20 * 8, 0, false, true));

        if (loser != null && loser.isOnline()) {
            loser.sendTitle("§c본인인증 실패", "§7상대가 먼저 인증했습니다", 5, 40, 10);
            loser.sendMessage("§c[본인인증] §f인증 경쟁에서 패배했습니다.");
            loser.playSound(loser.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 0.7f);

            loser.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20 * 8, 1, false, true));
            loser.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 20 * 8, 0, false, true));
        }
    }

    private void forceLeave(Player player) {
        PlayerAuthState state = states.remove(player.getUniqueId());
        if (state == null) return;

        AuthDuel duel = duels.remove(state.sessionId);
        if (duel == null) return;

        UUID otherId = duel.other(player.getUniqueId());
        states.remove(otherId);

        Player other = Bukkit.getPlayer(otherId);
        if (other != null && other.isOnline()) {
            other.sendMessage("§7[본인인증] 상대가 인증을 종료했습니다.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        forceLeave(event.getPlayer());
    }

    private String randomCode() {
        int n = ThreadLocalRandom.current().nextInt(1000, 10000);
        return String.valueOf(n);
    }

    private String displayInput(String input) {
        if (input == null || input.isEmpty()) return "____";

        StringBuilder sb = new StringBuilder(input);
        while (sb.length() < 4) {
            sb.append("_");
        }
        return sb.toString();
    }

    private enum Stage {
        CODE,
        ROBOT
    }

    private static class PlayerAuthState {
        final int sessionId;
        final UUID playerId;
        Stage stage = Stage.CODE;
        String code;
        String input = "";
        int noCount = 0;
        boolean correctAnswerYes;

        PlayerAuthState(int sessionId, UUID playerId) {
            this.sessionId = sessionId;
            this.playerId = playerId;
        }
    }

    private static class AuthDuel {
        final int sessionId;
        final UUID a;
        final UUID b;
        boolean finished = false;

        AuthDuel(int sessionId, UUID a, UUID b) {
            this.sessionId = sessionId;
            this.a = a;
            this.b = b;
        }

        UUID other(UUID one) {
            return one.equals(a) ? b : a;
        }
    }
}