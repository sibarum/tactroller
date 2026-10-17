package sibarum.tactroller.api;

/**
 * One finger on a touchpad's surface, as the touchpad reported it — raw contact data, before the OS turned it into
 * pointer motion, scrolling or a system gesture.
 *
 * <p>Positions are given twice, because the two uses want different things:
 *
 * <ul>
 *   <li>{@code x}/{@code y} — millimetres from the surface's top-left corner. Gesture thresholds and pinch
 *       distances belong in physical units: two millimetres is two millimetres on a small laptop pad and on a large
 *       external one, where a device-unit or normalized threshold would mean something different on each.</li>
 *   <li>{@code u}/{@code v} — position normalized to {@code [0, 1]} across the surface. Use it to map the pad
 *       absolutely onto something, e.g. a drawing area.</li>
 * </ul>
 *
 * <p>The surface is seen from above with {@code y} growing towards the user, the same orientation as screen
 * coordinates. A device that does not report its physical size gets an assumed one (see the backend), so
 * millimetres are then approximate but still proportional.
 *
 * @param id a number identifying this touch for as long as the finger stays down. Unique among the contacts present
 *           at one moment; may be reused once the finger lifts. Carries no meaning beyond identity.
 */
public record TouchpadContact(int id, double x, double y, double u, double v) {}
