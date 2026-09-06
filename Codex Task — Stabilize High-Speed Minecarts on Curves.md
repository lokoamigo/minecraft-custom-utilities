Modify the existing FastMinecarts Paper plugin to prevent high-speed minecarts from derailing or overshooting 90-degree rail curves.

Repository:
https://github.com/lokoamigo/minecraft-custom-utilities.git

Requirements:

1. Inspect the existing FastMinecarts implementation and modify the existing code rather than creating a duplicate plugin.

2. Preserve the current configurable high-speed minecart functionality:
   - configurable maximum speed in blocks/second
   - configurable acceleration
   - powered-rail acceleration
   - slow-when-empty setting
   - existing `/minecartspeed` command and reload behavior

3. Add configurable curve speed limiting:
   ```yaml
   curve-speed-blocks-per-second: 14.0
   ```

4. Detect curved rails using Bukkit/Paper `Rail.Shape`:
   - NORTH_EAST
   - NORTH_WEST
   - SOUTH_EAST
   - SOUTH_WEST

5. High-speed carts must detect curves BEFORE reaching them.
   - Look ahead along the current direction of travel.
   - Look-ahead distance should scale with current minecart velocity.
   - It must work for carts travelling in either direction.
   - Account for rails one block above or below where necessary for slopes/transitions.
   - Do not scan unrelated chunks/entities or perform global per-tick searches.

6. When a curve is detected ahead:
   - reduce the cart's actual velocity to at most `curve-speed-blocks-per-second`
   - do this early enough that the cart cannot skip over the curved rail
   - preserve the current direction of travel while braking

7. While the cart is physically on a curved rail:
   - ensure its speed remains capped at the configured curve speed
   - allow vanilla Minecraft rail physics to perform the actual turn
   - do not manually teleport the minecart
   - do not manually rotate it through the curve unless absolutely necessary

8. After leaving the curve:
   - allow the existing acceleration system to smoothly accelerate the cart back toward `speed-blocks-per-second`

9. Do NOT permanently enable `setNoPhysics(true)`.
   - Normal block/gravity physics should remain intact.
   - Do not solve derailment by making minecarts permanently noclip.

10. Keep vanilla-compatible defaults. A fresh/default configuration should behave essentially like vanilla:
   ```yaml
   speed-blocks-per-second: 8.0
   acceleration-blocks-per-second-squared: 0.0
   curve-speed-blocks-per-second: 8.0
   slow-when-empty: true
   boost-on-all-rails: false
   ```

11. Validate all numeric configuration:
   - reject NaN/infinity
   - reject negative values
   - clamp unreasonable maximum values using the plugin's existing limits
   - write corrected/default values back to config when appropriate

12. Performance requirements:
   - no scheduler that scans every minecart every tick
   - no `world.getEntities()` hot-path scans
   - operate only when minecart/vehicle events require it
   - cache/precompute blocks-per-tick and acceleration-per-tick values after config changes
   - avoid unnecessary vector allocations and square roots where practical
   - return early from hot event handlers whenever possible

13. Make rail lookup robust.
   Create/reuse a helper that finds the rail associated with a minecart by checking the current block and the block immediately below it.

14. Avoid breaking:
   - ascending rails
   - descending rails
   - powered rails
   - detector rails
   - activator rails
   - carts travelling backwards
   - empty minecarts
   - occupied minecarts

15. Update tab completion/status output if appropriate so the new curve-speed setting can be inspected or changed cleanly.

Preferred command syntax:
   ```
   /minecartspeed <speed>
   /minecartspeed acceleration <value>
   /minecartspeed curvespeed <value>
   /minecartspeed reload
   ```

16. Keep permission handling under:
   ```
   fastminecarts.admin
   ```

17. Review the whole FastMinecarts class while making the change:
   - remove dead imports/code
   - fix API misuse
   - fix obvious bugs
   - improve naming where useful
   - preserve behavior unless required by this task
   - ensure it compiles against the Paper version configured by the project

18. Run the project's available build/test command after changes.
   Fix compilation errors and warnings caused by the changes.

19. At completion, report:
   - files changed
   - important implementation decisions
   - configuration keys added/changed
   - commands added/changed
   - build/test result
   - any remaining edge cases

Important behavior target:

A cart travelling at 40–100 blocks/sec on straight track should begin braking before a Minecraft 90-degree curve, enter the curve at a safe configurable speed, let vanilla rail physics perform the turn, and then accelerate back to its configured straight-line speed after exiting the curve.

Do not merely increase `Minecart#setMaxSpeed()`. The plugin must manipulate actual velocity/acceleration because max speed is only a ceiling.