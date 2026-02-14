package my.pkg;

import my.pkg.abilities.*;
import org.bukkit.plugin.java.JavaPlugin;

public final class MyPlugin extends JavaPlugin {
    private AbilitySystem abilitySystem;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        abilitySystem = new AbilitySystem(this);
        abilitySystem.register(new MalphiteAbility());
        abilitySystem.register(new ViperAbility());
        abilitySystem.register(new KiyathowAbility());
        abilitySystem.register(new BombermanAbility());
        abilitySystem.register(new AntmanAbility());
        abilitySystem.register(new SpeedingAbility());
        abilitySystem.register(new GamblerAbility());
        abilitySystem.registerListeners();

        getCommand("ability").setExecutor(abilitySystem);
    }

    @Override
    public void onDisable() {
        if (abilitySystem != null) {
            abilitySystem.shutdown();
        }
    }
}
