package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class HumanCheckAbility implements Ability, Listener {

    private final JavaPlugin plugin;
    private final NamespacedKey actionKey;
    private final NamespacedKey valueKey;
    private final NamespacedKey sessionKey;

    private static final int COOLDOWN = 40;
    private static final int RANGE = 15;
    private static final int NEED_ROBOT_QUESTIONS = 5;

    private int nextSessionId = 1;

    private final Map<Integer, AuthDuel> duels = new HashMap<>();
    private final Map<UUID, PlayerAuthState> states = new HashMap<>();

    public HumanCheckAbility(JavaPlugin plugin) {
        this.plugin = plugin;
        this.actionKey = new NamespacedKey(plugin, "human_check_action");
        this.valueKey = new NamespacedKey(plugin, "human_check_value");
        this.sessionKey = new NamespacedKey(plugin, "human_check_session");
    }

    @Override
    public String id() {
        return "humancheck";
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

        PlayerAuthState casterState = new PlayerAuthState(sessionId, caster.getUniqueId(), caster.getLocation().clone());
        PlayerAuthState targetState = new PlayerAuthState(sessionId, target.getUniqueId(), target.getLocation().clone());

        states.put(caster.getUniqueId(), casterState);
        states.put(target.getUniqueId(), targetState);

        applyAuthProtection(caster);
        applyAuthProtection(target);

        caster.sendTitle("§b본인인증 시작", "§f상대보다 먼저 통과하세요", 5, 40, 10);
        target.sendTitle("§b본인인증 시작", "§f상대보다 먼저 통과하세요", 5, 40, 10);

        sendCodeStep(caster, casterState);
        sendCodeStep(target, targetState);

        return true;
    }

    private void sendCodeStep(Player player, PlayerAuthState state) {
        state.stage = Stage.CODE;
        state.code = randomCode();
        state.input = "";

        openCodeGui(player, state);
    }

    private void openCodeGui(Player player, PlayerAuthState state) {
        Inventory inv = Bukkit.createInventory(null, 27, "§b본인인증 §7- 코드 입력");

        inv.setItem(4, makeInfoItem(
                Material.PAPER,
                "§e인증코드: §l" + state.code,
                "§7현재 입력: §f" + displayInput(state.input)
        ));

        inv.setItem(10, makeButton(Material.WHITE_STAINED_GLASS_PANE, "§e1", "digit", "1", state.sessionId));
        inv.setItem(11, makeButton(Material.WHITE_STAINED_GLASS_PANE, "§e2", "digit", "2", state.sessionId));
        inv.setItem(12, makeButton(Material.WHITE_STAINED_GLASS_PANE, "§e3", "digit", "3", state.sessionId));

        inv.setItem(13, makeButton(Material.WHITE_STAINED_GLASS_PANE, "§e4", "digit", "4", state.sessionId));
        inv.setItem(14, makeButton(Material.WHITE_STAINED_GLASS_PANE, "§e5", "digit", "5", state.sessionId));
        inv.setItem(15, makeButton(Material.WHITE_STAINED_GLASS_PANE, "§e6", "digit", "6", state.sessionId));

        inv.setItem(16, makeButton(Material.WHITE_STAINED_GLASS_PANE, "§e7", "digit", "7", state.sessionId));
        inv.setItem(19, makeButton(Material.WHITE_STAINED_GLASS_PANE, "§e8", "digit", "8", state.sessionId));
        inv.setItem(20, makeButton(Material.WHITE_STAINED_GLASS_PANE, "§e9", "digit", "9", state.sessionId));
        inv.setItem(21, makeButton(Material.WHITE_STAINED_GLASS_PANE, "§e0", "digit", "0", state.sessionId));

        inv.setItem(23, makeButton(Material.RED_STAINED_GLASS_PANE, "§c지우기", "clear", "", state.sessionId));
        inv.setItem(25, makeButton(Material.LIME_STAINED_GLASS_PANE, "§a확인", "submit", "", state.sessionId));

        player.openInventory(inv);
    }

    private void sendRobotStep(Player player, PlayerAuthState state) {
        state.stage = Stage.ROBOT;
        state.noCount = 0;
        sendRobotQuestion(player, state);
    }

    private void sendRobotQuestion(Player player, PlayerAuthState state) {
        Question q = QUESTIONS[ThreadLocalRandom.current().nextInt(QUESTIONS.length)];
        state.correctAnswerYes = q.answerYes;
        openRobotGui(player, state, q.text);
    }

    private void openRobotGui(Player player, PlayerAuthState state, String question) {
        Inventory inv = Bukkit.createInventory(null, 27, "§b본인인증 §7- 로봇 검사");

        inv.setItem(4, makeInfoItem(
                Material.OBSERVER,
                "§f" + ChatColor.stripColor(question),
                "§7진행도: §f" + state.noCount + "/" + NEED_ROBOT_QUESTIONS
        ));

        inv.setItem(11, makeButton(Material.LIME_STAINED_GLASS_PANE, "§a예", "yes", "", state.sessionId));
        inv.setItem(15, makeButton(Material.RED_STAINED_GLASS_PANE, "§c아니오", "no", "", state.sessionId));

        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        PlayerAuthState state = states.get(player.getUniqueId());
        if (state == null) return;

        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) return;

        Integer session = meta.getPersistentDataContainer().get(sessionKey, PersistentDataType.INTEGER);
        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        String value = meta.getPersistentDataContainer().get(valueKey, PersistentDataType.STRING);

        if (session == null || action == null) return;
        if (session != state.sessionId) return;

        switch (action) {
            case "digit" -> handleDigit(player, state, value);
            case "clear" -> {
                state.input = "";
                openCodeGui(player, state);
            }
            case "submit" -> handleSubmit(player, state);
            case "yes" -> handleYes(player, state);
            case "no" -> handleNo(player, state);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        PlayerAuthState state = states.get(player.getUniqueId());
        if (state == null) return;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            PlayerAuthState latest = states.get(player.getUniqueId());
            if (latest == null) return;

            if (latest.stage == Stage.CODE) {
                openCodeGui(player, latest);
            } else {
                sendRobotQuestion(player, latest);
            }
        }, 2L);
    }

    private void handleDigit(Player player, PlayerAuthState state, String digit) {
        if (state.stage != Stage.CODE) return;
        if (digit == null || !digit.matches("[0-9]")) return;

        if (state.input.length() >= 4) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 0.8f);
            return;
        }

        state.input += digit;
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.4f);
        openCodeGui(player, state);
    }

    private void handleSubmit(Player player, PlayerAuthState state) {
        if (state.stage != Stage.CODE) return;

        if (state.input.equals(state.code)) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.8f);
            sendRobotStep(player, state);
        } else {
            player.sendMessage("§c[본인인증] 인증번호가 틀렸습니다. 처음부터 다시 진행합니다.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.7f);
            sendCodeStep(player, state);
        }
    }

    private void handleYes(Player player, PlayerAuthState state) {
        if (state.stage != Stage.ROBOT) return;

        if (state.correctAnswerYes) {
            state.noCount++;
            nextOrComplete(player, state);
        } else {
            failAndReset(player, state);
        }
    }

    private void handleNo(Player player, PlayerAuthState state) {
        if (state.stage != Stage.ROBOT) return;

        if (!state.correctAnswerYes) {
            state.noCount++;
            nextOrComplete(player, state);
        } else {
            failAndReset(player, state);
        }
    }

    private void nextOrComplete(Player player, PlayerAuthState state) {
        if (state.noCount >= NEED_ROBOT_QUESTIONS) {
            complete(player, state);
            return;
        }

        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.3f);
        sendRobotQuestion(player, state);
    }

    private void failAndReset(Player player, PlayerAuthState state) {
        player.sendMessage("§c[본인인증] 틀렸습니다! 처음부터 다시 인증합니다.");
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 0.8f);
        sendCodeStep(player, state);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        PlayerAuthState state = states.get(player.getUniqueId());
        if (state == null) return;
        if (event.getTo() == null) return;

        Location from = event.getFrom();
        Location to = event.getTo();

        boolean moved = from.getX() != to.getX()
                || from.getY() != to.getY()
                || from.getZ() != to.getZ();

        if (!moved) return;

        Location locked = state.lockLocation.clone();
        locked.setYaw(to.getYaw());
        locked.setPitch(to.getPitch());
        event.setTo(locked);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!states.containsKey(player.getUniqueId())) return;

        event.setCancelled(true);
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

        clearAuthProtection(winner);
        winner.closeInventory();

        winner.sendTitle("§a본인인증 성공!", "§f당신은 인간입니다", 5, 40, 10);
        winner.sendMessage("§a[본인인증] §f인증을 먼저 완료했습니다!");
        winner.playSound(winner.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);

        winner.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 8, 1, false, true));
        winner.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 20 * 8, 0, false, true));
        winner.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 20 * 8, 0, false, true));

        if (loser != null && loser.isOnline()) {
            clearAuthProtection(loser);
            loser.closeInventory();

            loser.sendTitle("§c본인인증 실패", "§7상대가 먼저 인증했습니다", 5, 40, 10);
            loser.sendMessage("§c[본인인증] §f인증 경쟁에서 패배했습니다.");
            loser.playSound(loser.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 0.7f);

            loser.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20 * 8, 1, false, true));
            loser.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 20 * 8, 0, false, true));
        }
    }

    private void applyAuthProtection(Player player) {
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.RESISTANCE,
                20 * 60,
                4,
                false,
                false
        ));
    }

    private void clearAuthProtection(Player player) {
        player.removePotionEffect(PotionEffectType.RESISTANCE);
    }

    private void forceLeave(Player player) {
        PlayerAuthState state = states.remove(player.getUniqueId());
        clearAuthProtection(player);
        player.closeInventory();

        if (state == null) return;

        AuthDuel duel = duels.remove(state.sessionId);
        if (duel == null) return;

        UUID otherId = duel.other(player.getUniqueId());
        states.remove(otherId);

        Player other = Bukkit.getPlayer(otherId);
        if (other != null && other.isOnline()) {
            clearAuthProtection(other);
            other.closeInventory();
            other.sendMessage("§7[본인인증] 상대가 인증을 종료했습니다.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        forceLeave(event.getPlayer());
    }

    private ItemStack makeButton(Material material, String name, String action, String value, int sessionId) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(name);
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        meta.getPersistentDataContainer().set(valueKey, PersistentDataType.STRING, value == null ? "" : value);
        meta.getPersistentDataContainer().set(sessionKey, PersistentDataType.INTEGER, sessionId);

        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeInfoItem(Material material, String name, String lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(name);
        meta.setLore(List.of(lore));

        item.setItemMeta(meta);
        return item;
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

    private static final Question[] QUESTIONS = {
            new Question("당신은 로봇입니까?", false),
            new Question("당신은 인간입니까?", true),
            new Question("이 질문에 '예'를 누르세요.", true),
            new Question("이 질문에 '아니오'를 누르세요.", false),
            new Question("당신은 자동화된 프로그램입니까?", false),
    };

    private enum Stage {
        CODE,
        ROBOT
    }

    private static class Question {
        final String text;
        final boolean answerYes;

        Question(String text, boolean answerYes) {
            this.text = text;
            this.answerYes = answerYes;
        }
    }

    private static class PlayerAuthState {
        final int sessionId;
        final UUID playerId;
        final Location lockLocation;

        Stage stage = Stage.CODE;
        String code;
        String input = "";
        int noCount = 0;
        boolean correctAnswerYes;

        PlayerAuthState(int sessionId, UUID playerId, Location lockLocation) {
            this.sessionId = sessionId;
            this.playerId = playerId;
            this.lockLocation = lockLocation;
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