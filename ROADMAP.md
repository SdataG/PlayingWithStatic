# Playing With Static — Build Roadmap

Custom lightning + Tesla-inspired electrical power mod for NeoForge 1.21.1.
Spun off from [Sunwell](../sunwell)'s existing lightning VFX, which is kept as
implementation reference only (not a dependency — separate mod, separate repo).

## Concept

Replace vanilla Minecraft lightning's *render* with a custom branching bolt
that grows from the sky downward — long reaching branches that search for a
surface, favoring lightning rods — then resolves into a strike effect. Build a
Tesla-inspired electrical power system on top of it, using real strike energy
as an actual gameplay-relevant power source instead of a purely cosmetic
effect.

Design north star: lean on real Nikola Tesla-era electrical concepts
(wireless power transmission, induction, grounding/insulation), but only
where they fill an actual mechanical gap vanilla doesn't already cover — not
a generic tech-mod clone.

## Prior art / reference (Sunwell repo — read, don't copy wholesale)

- `SunwellBoltRenderer` — jagged/branching bolt renderer, recursive
  sub-forks, raycast-clipped branch lengths, timed over `LIFE_TICKS = 10` in
  three beats (leader growth → return-stroke pulse → fade).
- `LightningFlashLight` — fading in-world light flash at strike point.
- `LightningBoltRendererMixin` — cancels vanilla bolt render, calls custom
  renderer instead. This is the technique to reuse for hooking vanilla's
  `LightningBolt` entity here.
- `SunwellManager` — custom targeting/rod-seeking logic. **Not reused** —
  this mod deliberately does not reimplement targeting (see locked decisions).

## Locked technical decisions

1. **Separate repo, own NeoForge mod, own mod_id** (`playingwithstatic`).
   Target MC 1.21.1 to match Sunwell's current branch unless later changed.
2. **No custom targeting or strike-frequency logic.** Vanilla already rolls
   strike chance during storms and redirects to the nearest eligible
   lightning rod before the `LightningBolt` entity spawns. By the time the
   entity exists, its position is already the final, rod-aware target.
   Mixin-cancel vanilla's render of that entity and only render a custom
   growth animation from sky down to the entity's already-known position.
   Branches during growth are cosmetic surface-seeking flavor, not real
   targeting.
3. **Sky origin point** = same X/Z as the vanilla strike position, projected
   up to sky height; leader grows straight down to the entity's actual Y
   (which may already be a rod's position).
4. **Timing**: reuse Sunwell's `LIFE_TICKS = 10` three-beat structure (leader
   growth / return-stroke pulse / fade) so thunder/sound retiming logic stays
   compatible without rework.
5. **Power system**: Forge Energy (FE) — the standard most Create-adjacent
   electric mods interop with — plus a rotational↔FE converter block for
   direct Create kinetic-stress integration. Create is a soft/optional
   integration point, not a hard dependency.

## Phase 1 — MVP

Everything else depends on this existing first.

- [ ] Branching sky-to-surface lightning VFX (per locked decisions above).
- [ ] Copper lightning rod behavior: catches the vanilla-redirected strike
      (reuse/extend vanilla's existing copper lightning rod block).
- [ ] Capacitor block: **craftable** power-storage block (not
      world-generated) that charges when a strike lands on/near it or is
      routed through a connected rod. Converts strike energy directly into
      stored FE.
- [ ] Basic FE storage/output so the capacitor can power something, proving
      the power spine end-to-end.

## Phase 2 — power distribution & defense

- [ ] Tesla coils, two tiers:
  - [ ] Small coil: wireless FE *receiver* for nearby powered devices.
  - [ ] Large coil: wireless FE *broadcaster* — also usable as a
        base-defense turret, discharging into unshielded conductors
        (mobs/players) that get close.
- [ ] Chain armor rework: acts as an insulator/Faraday cage, letting the
      wearer safely stand near active large tesla coils.
- [ ] Rotational↔FE converter block for Create integration.

## Phase 3 — low-tier / flavor power source

- [ ] Sheep static pins: penned sheep generate a tiny trickle of static
      charge (wool friction) — early-game power source that doesn't require
      a storm.

## Backlog (explicitly later, not in early scope)

- Ball lightning: new standalone weather phenomenon.
- Handheld gun weapon with a ball-lightning attachment/ammo variant.

## Open questions to resolve as building starts

- Exact FE storage/output tuning per tier (capacitor, small coil, large
  coil).
- Large tesla coil damage/range balance and multiplayer griefing
  considerations (unshielded players near an enemy's coil).
- Whether the copper rod itself generates power on a direct hit, or strictly
  redirects visually while all generation happens at the capacitor block
  (leaning: rod redirects only, capacitor generates — keeps power logic in
  one place).
- Block/item designs for the capacitor, tesla coils, and converter (visual
  design not yet started).
- Final mod name/branding (currently "Playing With Static").

## Build steps

1. [x] Scaffold new NeoForge 1.21.1 mod repo (own mod_id, `neoforge.mods.toml`,
       basic registries). — done, see `PlayingWithStatic.java` / `ModBlocks.java`.
2. [ ] Port/adapt the branching bolt renderer + sky-origin growth logic (new
       growth-phase behavior, not a straight copy of `SunwellBoltRenderer`).
3. [ ] Add the render-cancel mixin on vanilla `LightningBoltRenderer`,
       matching Sunwell's `LightningBoltRendererMixin` approach.
4. [ ] Verify: natural vanilla strikes now render as the new branching
       effect, still respect vanilla's own rod-redirect and frequency, at the
       same `LIFE_TICKS=10` pacing as Sunwell's bolt.
5. [ ] Add FE capability to a new capacitor block; wire a strike-landed event
       (from the mixin/entity hook) to charge it.
6. [ ] From there, layer in tesla coils, chain armor changes, sheep static
       pins.
