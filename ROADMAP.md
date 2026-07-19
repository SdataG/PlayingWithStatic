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

Balance philosophy: a real strike delivers a lot of power but is rare and
storm-gated (vanilla frequency, not reimplemented — see locked decision 2),
so the power spine rewards infrastructure built to catch and hold an
uncertain, bursty supply — capacitor banks, overflow handling — rather than
steady drip-feed generation like most tech mods' generators.

Plays like a reactor, but an *early*-game one: a single copper rod and
capacitor is cheap and gets you a huge burst of power the moment a storm
rolls in — no tech-tree gate like most mods put in front of their
high-output/high-risk generators. The risk (rupture into ball lightning)
is the tradeoff for that early access, and it scales with how much you're
trying to hold (bank size) rather than with tier — so "meltdown" containment
investment (banks, relief valves) is a choice players make in response to
how much power they're chasing, not a fixed late-game unlock.

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
2. **Targeting within a chunk stays vanilla; frequency across chunks gets
   terrain-aware.** Vanilla already picks strike *location* via heightmap
   (highest block in the column) and redirects to the highest-in-column
   lightning rod within 128 blocks — that part is not reimplemented, and by
   the time the `LightningBolt` entity exists its position is already the
   final, rod-aware target. Mixin-cancel vanilla's render of that entity and
   only render a custom growth animation from sky down to the entity's
   already-known position; branches during growth stay cosmetic
   surface-seeking flavor, not real targeting.
   What *is* added: a frequency multiplier layered on top of vanilla's
   per-chunk-per-tick strike roll, biased by terrain — mountains, plateaus,
   and high plains get struck more often; low-elevation/sheltered terrain
   less. Environment affects how often a chunk gets picked for a strike
   attempt, never where within that chunk the bolt lands.
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
6. **Recipes**: progression-tiered using vanilla materials first, and
   deliberately distinct from other tech mods' signature recipes so this
   doesn't read as a reskin.
   - Lean on vanilla materials before inventing custom alloys/ingots: copper
     (already the strike-catching tier vanilla established via lightning
     rods) for entry-level parts, iron/redstone for wiring and basic FE
     handling, amethyst for anything wireless/resonant — it already carries
     a "signal/vibration" association in vanilla via sculk sensors, so it
     fits the tesla coils' wireless transmission thematically — gold/diamond
     reserved for higher tiers.
   - Avoid shapes/ingredient sets that closely mirror other popular tech
     mods' machine-frame recipes (Mekanism, Immersive Engineering, Create,
     Applied Energistics) to keep this from clashing or reading derivative.
7. **Multiblock construction.** Most Phase 2+ devices (tesla coils,
   capacitor banks, induction pads, redstone bridge, voltmeter) are built by
   assembling smaller component blocks into a multiblock, not delivered as a
   single always-on block. This is also how capacity scales — a bank is
   several capacitor blocks linked together, not one block with a bigger
   number.

## Phase 1 — MVP

Everything else depends on this existing first.

- [ ] Branching sky-to-surface lightning VFX (per locked decisions above).
- [ ] Terrain-aware strike frequency: hook vanilla's per-chunk-per-tick
      strike roll and apply a terrain-based multiplier during storms —
      boosted for mountains, plateaus, and high plains; standard or reduced
      elsewhere. Location selection within the chosen chunk stays 100%
      vanilla (per decision 2) — this only changes how often a chunk gets
      picked at all.
- [ ] Copper lightning rod behavior: catches the vanilla-redirected strike
      (reuse/extend vanilla's existing copper lightning rod block).
- [ ] Capacitor block: **craftable** power-storage block (not
      world-generated) that charges when a strike lands on/near it or is
      routed through a connected rod. Converts strike energy directly into
      stored FE.
- [ ] Capacitor visual: transparent shell with lightning arcing/bouncing
      inside, visibly condensing/settling as charge climbs toward full.
- [ ] Capacitor overload/rupture: if strike energy comes in faster than the
      capacitor's contain rate (over capacity, or nothing drawing power off
      it fast enough), it ruptures — explodes and releases a live ball
      lightning entity as a hazard. First appearance of "ball lightning" as
      a capacitor failure state, ahead of the standalone weather-phenomenon
      version in the backlog below.
- [ ] Capacitor banks: link multiple capacitor blocks into a multiblock (see
      locked decision 7) to hold more than one strike's worth of charge.
      Bigger banks store more but also scale the overload/rupture
      consequence if mismanaged — a large bank going critical is a much
      bigger event than a single capacitor popping.
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
- [ ] Late-game overflow drain / relief valve: a multiblock addition to a
      capacitor bank that bleeds excess charge off safely (e.g. into heat,
      light, or a vanilla redstone pulse) once a bank nears its rupture
      threshold — the payoff for investing in enough infrastructure to run
      big banks without them going critical into ball lightning.

## Phase 3 — low-tier / flavor power source

- [ ] Sheep static pins: penned sheep generate a tiny trickle of static
      charge (wool friction) — early-game power source that doesn't require
      a storm.
- [ ] Cats as an additional static source: real-world cat fur builds static
      too — petting/rubbing interaction (or cats loitering in/near the pen)
      contributes a small charge alongside the sheep mechanic.

## Phase 4 — mod compatibility & integration (end of project)

Deliberately last: get the mod's own mechanics right on their own first,
then build interop once the core is stable rather than designing around
other mods' APIs from day one.

- [ ] Rotational↔FE converter block for Create integration (moved here from
      Phase 2 — Create compat waits until the core spine works standalone).
- [ ] Create: New Age compat.
- [ ] Other FE-based power mods as they come up (e.g. Mekanism, Immersive
      Engineering) — energy interop, not full feature parity.

## Backlog (explicitly later, not in early scope)

- Ball lightning as a standalone weather phenomenon (independent of capacitor
  overload — see Phase 1 for its first appearance as a failure state).
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
- Exact recipe shapes/ingredient costs per block, once block designs are
  locked (see recipe principles in locked decision 6).
- Final mod name/branding (currently "Playing With Static").
- Terrain-frequency classification: vanilla has a `#minecraft:is_mountain`
  biome tag (Meadow, Grove, Snowy Slopes, Jagged/Frozen/Stony Peaks,
  Windswept Hills/Forest/Gravelly Hills), but no tag for "plateau" or "high
  plain" — those read more as elevation/flatness at a spot than a biome
  identity (e.g. Savanna Plateau is a biome, but a plain simply sitting at
  high Y isn't). Decide whether classification is biome-tag-based,
  terrain-height-based (surface Y / local prominence at the strike column),
  or a mix — and what the actual frequency multipliers should be per
  category.
- Research real-world lightning-protection siting: utilities and
  researchers deliberately place freestanding rods/masts out in open fields,
  away from structures, with their own isolated grounding grid, for safety.
  Worth mining for mechanics — e.g. a large coil or collector tower placed
  away from a base being safer/more reliable than one bolted onto it, or a
  dedicated "field pole" structure players are incentivized to build
  off-base rather than a pure defense-turret framing.

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
