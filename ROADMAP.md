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
uncertain, bursty supply — cage battery banks, overflow handling — rather
than steady drip-feed generation like most tech mods' generators.

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
   surface-seeking flavor, not real targeting — implemented (see Phase 1)
   as: a branch raycasts against block collision *and* nearby living
   entities (mobs/players), stopping at whichever it reaches first instead
   of visibly passing through a mob or player the way it would through
   thin air; and if a copper rod is within range, branches are weighted
   (not forced) to reach toward it, so a rod nearby noticeably pulls
   branches its way but a branch can still land elsewhere less often.
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
7. **Multiblock construction.** Most Phase 2+ devices (tesla coils, cage
   battery banks, induction pads, redstone bridge, voltmeter) are built by
   assembling smaller component blocks into a multiblock, not delivered as a
   single always-on block. This is also how capacity scales — a bank is
   several cage battery blocks linked together, not one block with a bigger
   number.
8. **Overflow explosion is capability-based, not mod-specific.** The
   capacitor (locked decision 5's power spine, detailed in Phase 1) has
   effectively infinite intake but must keep draining what it takes in —
   into our cage battery, or into any other mod's block exposing NeoForge's
   energy capability. If it has no valid output at all, or whatever it's
   draining into is full (even 1 FE over remaining capacity), with no other
   drain/ground path available, something ruptures. Plugging a foreign
   mod's battery into the strike-catching spine and overloading it blows it
   up too, same as ours — the danger is a property of catching raw
   lightning, not of any specific block. See "Ball lightning" below for
   what a rupture actually does.
9. **Terrain-frequency classification: hybrid.** Elevation (surface Y /
   local prominence at the strike column) sets the base frequency
   multiplier; biome tags (`#minecraft:is_mountain`, badlands, savanna
   plateau, etc.) stack an additional boost on top. Resolves the earlier
   open question — elevation alone would have missed real named-biome
   flavor, biome tags alone can't cover a plain that just happens to sit
   high.
10. **Strike power scale is anchored to a common FE mod's early battery,
    not tuned only against our own blocks.** A raw strike needs to be able
    to threaten to overflow a well-known mod's basic battery (e.g. a
    Mekanism or Immersive Engineering starter battery), or locked decision
    8's cross-mod danger never actually shows up in play. Exact numbers are
    still an open question (see below) — this only locks in the *approach*.

## Phase 1 — MVP

Everything else depends on this existing first.

- [x] Branching sky-to-surface lightning VFX (per locked decisions above) —
      implemented, including entity-aware branch reach and rod-attraction
      bias (decision 2).
- [ ] Terrain-aware strike frequency: hook vanilla's per-chunk-per-tick
      strike roll and apply a terrain-based multiplier during storms —
      boosted for mountains, plateaus, and high plains; standard or reduced
      elsewhere. Location selection within the chosen chunk stays 100%
      vanilla (per decision 2) — this only changes how often a chunk gets
      picked at all.
- [ ] Copper lightning rod behavior: catches the vanilla-redirected strike
      and redirects it into a connected capacitor (reuse/extend vanilla's
      existing copper lightning rod block). The rod itself never generates
      power — see the capacitor below for where conversion happens.
- [ ] Capacitor block: sits between the rod and whatever stores the power.
      Effectively infinite instantaneous intake — it never fails to catch a
      strike no matter the size — converts raw voltage into FE and
      immediately starts draining it out to whatever's connected downstream
      (our cage battery, a foreign mod's FE storage, a machine, anything
      with the energy capability). A meter on the side fills per strike and
      drains back down as it pushes power out. If it has no valid output —
      nothing connected, or the output path is destroyed — it can't drain
      what it's holding, and *that* is the real overload trigger (see "Ball
      lightning" below), not the capacitor running out of room.
- [ ] Cage battery block (our own dedicated storage block, downstream of
      the capacitor): **craftable**, not world-generated, charges from a
      connected capacitor's output. Stores charge as a contained, tamed
      ball lightning orb inside the cage rather than an abstract FE number
      — purpose-built to hold one safely, unlike a generic battery, but
      still capacity-limited: overfeeding it past full is its own overload
      trigger (see "Ball lightning" below).
- [ ] Cage battery visual: transparent/latticed shell with the caged orb
      visibly arcing/bouncing and condensing as charge climbs toward full.
- [ ] Cage battery tiers: expandable multiblock — 1x1, 2x2, 3x3, 4x4 and up
      (see locked decision 7). Bigger cages are pricier but hold more bolts;
      capacity scales with size, and so does the disaster if one goes
      critical (see "Ball lightning" below).
- [ ] Basic FE storage/output so the cage battery can power something,
      proving the power spine end-to-end.

## Ball lightning — the overload hazard

The consequence of catching more strike energy than can safely be held.
Not limited to our own blocks — see locked decision 8 — any FE storage
overloaded with nowhere for the excess to go can trigger this.

- [ ] Trigger, two paths to the same result:
  - The **capacitor** has no valid output — nothing connected downstream,
    or the output path got destroyed mid-storm — so it can't drain what
    it just took in.
  - Whatever the capacitor *is* draining into (cage battery, or a foreign
    mod's FE storage) receives more than its remaining capacity (even 1 FE
    over is enough), with no other drain/ground path available.
  Either way: it ruptures — explodes, and releases a live ball lightning
  orb.
- [ ] Orb size scales with whatever ruptured — a single cage battery
      popping is a small, containable orb; a large cage battery bank going
      critical is a much bigger one.
- [ ] Orb travel: roams under its own power and temporarily force-loads the
      chunks it passes through (Create-train-style) so it keeps moving and
      dealing damage even with no player nearby — a large enough orb can
      travel far enough to reach and threaten another base entirely.
- [ ] Orb destruction: breaks terrain and kills mobs/players along its path.
- [ ] Orb attraction: drawn toward players, mobs, and copper rods.
- [ ] Rod interaction: an orb can drain into a copper rod, but the rod
      melts in the process (sacrificial, not a clean fix) — and if that rod
      feeds a connected battery/bank, the drained power can overload *that*
      battery too, chaining the disaster into another base's power grid.
- [ ] Neutralizing an orb: draining it with dedicated apparatus (ties into
      the late-game overflow relief valve in Phase 2) is the safe way to end
      one before it reaches something else.

Deliberately framed as a rare, devastating event, not a routine hazard.

## Phase 2 — power distribution & defense

- [ ] Tesla coils, two tiers:
  - [ ] Small coil: wireless FE *receiver* for nearby powered devices.
  - [ ] Large coil: wireless FE *broadcaster* — also usable as a
        base-defense turret, discharging into unshielded conductors
        (mobs/players) that get close.
- [ ] Chain armor rework: acts as an insulator/Faraday cage, letting the
      wearer safely stand near active large tesla coils.
- [ ] Late-game overflow drain / relief valve: a multiblock addition to a
      cage battery bank (or to a capacitor with no other output) that
      bleeds excess charge off safely (e.g. into heat, light, or a vanilla
      redstone pulse) once it nears its rupture threshold — the payoff for
      investing in enough infrastructure to run big banks, or leave a
      capacitor briefly unattended, without going critical into ball
      lightning.

## Phase 3 — low-tier / flavor power source

- [ ] Static collector: a block that wraps around a placed copper lightning
      rod (own model added — geometry only for now, same as the capacitor).
      Generates power but does **not** store it — a very low, passive
      trickle fed by ambient static in its immediate surroundings: mobs
      walking on/near it, carpet, and other nearby items. Storm-independent,
      like the sheep/cat sources below, and feeds whatever the rod is
      connected to (a capacitor) rather than holding charge itself.
- [ ] Sheep static pins: penned sheep generate a tiny trickle of static
      charge (wool friction) — early-game power source that doesn't require
      a storm. Read as the *concentrated* case of the static collector's
      general mechanism above (pen sheep next to a rod+collector to boost
      its yield), not a separate system.
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
- [ ] Sunwell compat: both mods redirect the identical
      `Level.playLocalSound` call on `LightningBolt.tick` to retime thunder
      to their own strike moment. `@Redirect` claims a specific instruction
      — only one mod can win it. Currently both sides are `require = 0` so
      losing the race degrades to doubled thunder instead of crashing (this
      crashed the game before that fix — see build history), but the real
      fix is coordinating which mod owns the redirect, or restructuring so
      they don't collide at all (e.g. one mod detects the other's presence
      and defers).

## Backlog (explicitly later, not in early scope)

- Ball lightning as a standalone weather phenomenon (independent of the
  overload hazard — see the "Ball lightning" section above for its first
  appearance as a failure state).
- Handheld gun weapon with a ball-lightning attachment/ammo variant.

## Open questions to resolve as building starts

- Exact FE numbers: strike output, capacitor drain rate, cage battery
  capacity per tier, small/large coil throughput — and specifically what
  number makes a raw strike genuinely threaten to overflow a real reference
  mod's basic battery (see locked decision 10 for the chosen approach; the
  actual figures still need picking, likely against a specific target like
  Mekanism's or Immersive Engineering's basic battery capacity).
- Ball lightning orb travel: how far a large orb should realistically roam,
  how long a force-loaded chunk stays loaded behind it, and how this is
  actually implemented (Create's train chunk-loading is the reference point,
  not something to copy wholesale).
- Orb-neutralizing apparatus: what the "drain it safely" tool/block actually
  is and what tier it unlocks at (see late-game overflow relief valve in
  Phase 2).
- Large tesla coil damage/range balance and multiplayer griefing
  considerations (unshielded players near an enemy's coil).
- Block/item designs for the rod, capacitor, cage battery, tesla coils, and
  converter (visual design not yet started).
- Exact recipe shapes/ingredient costs per block, once block designs are
  locked (see recipe principles in locked decision 6).
- Terrain frequency multiplier values: locked decision 9 sets the
  *approach* (elevation base + biome tag boost), but not the actual numbers
  per terrain category yet.
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
       (from the mixin/entity hook, via the rod) to feed it. Give it the
       infinite-intake/output-meter behavior from Phase 1, and wire the
       no-valid-output rupture path.
6. [ ] Add FE capability to a new cage battery block downstream of the
       capacitor; wire the over-capacity rupture path.
7. [ ] From there, layer in tesla coils, chain armor changes, sheep static
       pins.
