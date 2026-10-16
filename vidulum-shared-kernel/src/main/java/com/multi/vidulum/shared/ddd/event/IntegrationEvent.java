package com.multi.vidulum.shared.ddd.event;

/**
 * A payload that leaves the process: it is serialised onto a topic and read by consumers its
 * producer does not know.
 *
 * <p><b>Not the same thing as {@link DomainEvent}</b>, and the difference is the reason both exist:
 *
 * <table border="1">
 *   <caption>two kinds of event</caption>
 *   <tr><th></th><th>{@code DomainEvent}</th><th>{@code IntegrationEvent}</th></tr>
 *   <tr><td>what it is</td><td>a fact inside an aggregate</td><td>a fact leaving a module</td></tr>
 *   <tr><td>what happens to it</td><td>collected in the aggregate, stored through
 *       {@link StoredDomainEvent}</td><td>serialised and published</td></tr>
 *   <tr><td>who reads it</td><td>this module, later</td><td>anyone subscribed</td></tr>
 * </table>
 *
 * <p>A type can be both, and {@code CashFlowEvent} is: it is stored as a domain event and travels
 * inside the {@code CashFlowUnifiedEvent} envelope. The marker belongs on <b>what is actually
 * serialised</b> — the envelope — because that is the shape consumers are committed to.
 *
 * <p>Being serialised is what creates the obligation: the shape is a contract towards readers who
 * cannot be found and changed in one commit. Hence records (step 05c of the module split), and
 * hence this marker — "what leaves this system" should be a question with an answer.
 */
public interface IntegrationEvent {
}
