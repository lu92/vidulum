package com.multi.vidulum.shared.module;

/**
 * What another module may <b>ask</b> of this one: the inbound half of a module contract.
 *
 * <p>Implemented by the module that owns the answer, always package-private, and declared in that
 * module's {@code *-api} artifact. Nobody outside names the implementation — see
 * {@code PortfolioModuleApiImpl}.
 *
 * <p><b>This type is never used as a variable type.</b> It has no methods and holds nothing; a
 * {@code List<ModuleApi>} would be a sign that someone mistook it for an abstraction. It exists so
 * that two questions have an answer in one keystroke rather than in a grep over naming
 * conventions:
 *
 * <ul>
 *   <li><i>what can be asked of which module</i> — the type hierarchy of this interface;</li>
 *   <li><i>where is the implementation</i> — one level further down the same hierarchy.</li>
 * </ul>
 *
 * <p>Its other consumer is {@code ModuleBoundariesArchTest}, which keys its rules on this marker:
 * a contract is an interface, it lives in an {@code ..api..} package, and its implementation is not
 * public. Without those rules this marker would be decoration, which is why it arrived together
 * with them.
 *
 * @see ModuleSpi for the opposite direction — a socket somebody else plugs into
 * @see ModuleEventHandler for reacting to what another module announces
 */
public interface ModuleApi {
}
