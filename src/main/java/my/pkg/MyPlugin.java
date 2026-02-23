package my.pkg;

import my.pkg.abilities.*;
import org.bukkit.plugin.java.JavaPlugin;

public final class MyPlugin extends JavaPlugin {
    private AbilitySystem abilitySystem;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.abilitySystem = new AbilitySystem(this);

        abilitySystem.register(new MalphiteAbility());
        abilitySystem.register(new ViperAbility());
        abilitySystem.register(new KiyathowAbility());
        abilitySystem.register(new BombermanAbility());
        abilitySystem.register(new AntmanAbility());
        abilitySystem.register(new SpeedingAbility());
        abilitySystem.register(new GamblerAbility());
        abilitySystem.register(new SniperAbility(this));
        abilitySystem.register(new donationAbility(this));
        abilitySystem.register(new TaliyahAbility(this));
        abilitySystem.register(new JokerAbility());
        abilitySystem.register(new PanicAbility());
        abilitySystem.register(new GlowAbility());
        abilitySystem.register(new BodyguardAbility(this));
        abilitySystem.register(new HitmanAbility(this));

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
