// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.domain.registry.flows.domain;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Predicates.equalTo;
import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.collect.Iterables.any;
import static com.google.common.collect.Iterables.concat;
import static com.google.domain.registry.flows.EppXmlTransformer.unmarshal;
import static com.google.domain.registry.model.ofy.ObjectifyService.ofy;
import static com.google.domain.registry.model.registry.Registries.findTldForName;
import static com.google.domain.registry.model.registry.label.ReservedList.getReservation;
import static com.google.domain.registry.tldconfig.idn.IdnLabelValidator.findValidIdnTableForTld;
import static com.google.domain.registry.util.CollectionUtils.nullToEmpty;
import static com.google.domain.registry.util.DateTimeUtils.isAtOrAfter;
import static com.google.domain.registry.util.DomainNameUtils.ACE_PREFIX;

import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.net.InternetDomainName;
import com.google.domain.registry.flows.EppException;
import com.google.domain.registry.flows.EppException.AuthorizationErrorException;
import com.google.domain.registry.flows.EppException.ObjectDoesNotExistException;
import com.google.domain.registry.flows.EppException.ParameterValuePolicyErrorException;
import com.google.domain.registry.flows.EppException.ParameterValueRangeErrorException;
import com.google.domain.registry.flows.EppException.ParameterValueSyntaxErrorException;
import com.google.domain.registry.flows.EppException.RequiredParameterMissingException;
import com.google.domain.registry.flows.EppException.StatusProhibitsOperationException;
import com.google.domain.registry.flows.EppException.UnimplementedOptionException;
import com.google.domain.registry.model.EppResource;
import com.google.domain.registry.model.billing.BillingEvent;
import com.google.domain.registry.model.billing.BillingEvent.Flag;
import com.google.domain.registry.model.billing.BillingEvent.Reason;
import com.google.domain.registry.model.contact.ContactResource;
import com.google.domain.registry.model.domain.DesignatedContact;
import com.google.domain.registry.model.domain.DesignatedContact.Type;
import com.google.domain.registry.model.domain.DomainBase;
import com.google.domain.registry.model.domain.DomainCommand.CreateOrUpdate;
import com.google.domain.registry.model.domain.DomainCommand.InvalidReferenceException;
import com.google.domain.registry.model.domain.DomainResource;
import com.google.domain.registry.model.domain.Period;
import com.google.domain.registry.model.domain.ReferenceUnion;
import com.google.domain.registry.model.domain.fee.BaseFeeCommand;
import com.google.domain.registry.model.domain.fee.BaseFeeRequest;
import com.google.domain.registry.model.domain.fee.BaseFeeResponse;
import com.google.domain.registry.model.domain.fee.Fee;
import com.google.domain.registry.model.domain.fee.FeeCommandDescriptor;
import com.google.domain.registry.model.domain.launch.LaunchExtension;
import com.google.domain.registry.model.domain.launch.LaunchPhase;
import com.google.domain.registry.model.domain.secdns.DelegationSignerData;
import com.google.domain.registry.model.eppcommon.StatusValue;
import com.google.domain.registry.model.eppinput.ResourceCommand.SingleResourceCommand;
import com.google.domain.registry.model.host.HostResource;
import com.google.domain.registry.model.mark.Mark;
import com.google.domain.registry.model.mark.ProtectedMark;
import com.google.domain.registry.model.mark.Trademark;
import com.google.domain.registry.model.poll.PollMessage;
import com.google.domain.registry.model.registrar.Registrar;
import com.google.domain.registry.model.registry.Registry;
import com.google.domain.registry.model.registry.Registry.TldState;
import com.google.domain.registry.model.registry.label.ReservationType;
import com.google.domain.registry.model.reporting.HistoryEntry;
import com.google.domain.registry.model.smd.AbstractSignedMark;
import com.google.domain.registry.model.smd.EncodedSignedMark;
import com.google.domain.registry.model.smd.SignedMark;
import com.google.domain.registry.model.smd.SignedMarkRevocationList;
import com.google.domain.registry.tmch.TmchXmlSignature;
import com.google.domain.registry.tmch.TmchXmlSignature.CertificateSignatureException;
import com.google.domain.registry.util.Idn;

import com.googlecode.objectify.Key;

import org.joda.money.Money;
import org.joda.time.DateTime;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.GeneralSecurityException;
import java.security.SignatureException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.CertificateRevokedException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.xml.crypto.MarshalException;
import javax.xml.crypto.dsig.XMLSignatureException;
import javax.xml.parsers.ParserConfigurationException;

/** Static utility functions for domain flows. */
public class DomainFlowUtils {

  /** Map from launch phases to the equivalent tld states. */
  private static final ImmutableMap<LaunchPhase, TldState> LAUNCH_PHASE_TO_TLD_STATE =
      ImmutableMap.of(
          LaunchPhase.SUNRISE, TldState.SUNRISE,
          LaunchPhase.SUNRUSH, TldState.SUNRUSH,
          LaunchPhase.LANDRUSH, TldState.LANDRUSH,
          LaunchPhase.CLAIMS, TldState.GENERAL_AVAILABILITY,
          LaunchPhase.OPEN, TldState.GENERAL_AVAILABILITY);

  /** Non-sunrise tld states. */
  public static final ImmutableSet<TldState> DISALLOWED_TLD_STATES_FOR_LAUNCH_FLOWS =
      Sets.immutableEnumSet(
          TldState.PREDELEGATION,
          TldState.QUIET_PERIOD,
          TldState.GENERAL_AVAILABILITY);

  /** Reservation types that are allowed in sunrise by policy. */
  public static final ImmutableSet<ReservationType> TYPES_ALLOWED_FOR_CREATE_ONLY_IN_SUNRISE =
      Sets.immutableEnumSet(
          ReservationType.ALLOWED_IN_SUNRISE,
          ReservationType.NAME_COLLISION,
          ReservationType.MISTAKEN_PREMIUM);

  /** Strict validator for ascii lowercase letters, digits, and "-", allowing "." as a separator */
  private static final CharMatcher ALLOWED_CHARS =
      CharMatcher.inRange('a', 'z').or(CharMatcher.inRange('0', '9').or(CharMatcher.anyOf("-.")));

  /** The maximum number of DS records allowed on a domain. */
  private static final int MAX_DS_RECORDS_PER_DOMAIN = 8;

  /** Maximum number of nameservers allowed per domain. */
  private static final int MAX_NAMESERVERS_PER_DOMAIN = 13;

  /** Maximum number of characters in a domain label, from RFC 2181. */
  private static final int MAX_LABEL_SIZE = 63;

  /**
   * Returns parsed version of {@code name} if domain name label follows our naming rules and is
   * under one of the given allowed TLDs.
   *
   * <p><b>Note:</b> This method does not perform language validation with IDN tables.
   *
   * @see #validateDomainNameWithIdnTables(InternetDomainName)
   */
  static InternetDomainName validateDomainName(String name)
      throws EppException {
    if (!ALLOWED_CHARS.matchesAllOf(name)) {
      throw new BadDomainNameCharacterException();
    }
    List<String> parts = Splitter.on('.').splitToList(name);
    if (parts.size() <= 1) {
      throw new BadDomainNamePartsCountException();
    }
    if (any(parts, equalTo(""))) {
      throw new EmptyDomainNamePartException();
    }
    validateFirstLabel(parts.get(0));
    InternetDomainName domainName = InternetDomainName.from(name);
    Optional<InternetDomainName> tldParsed = findTldForName(domainName);
    if (!tldParsed.isPresent()) {
      throw new TldDoesNotExistException(domainName.parent().toString());
    }
    if (domainName.parts().size() != tldParsed.get().parts().size() + 1) {
      throw new BadDomainNamePartsCountException();
    }
    return domainName;
  }

  private static void validateFirstLabel(String firstLabel) throws EppException {
    if (firstLabel.length() > MAX_LABEL_SIZE) {
      throw new DomainLabelTooLongException();
    }
    if (firstLabel.startsWith("-")) {
      throw new LeadingDashException();
    }
    if (firstLabel.endsWith("-")) {
      throw new TrailingDashException();
    }
    String unicode = Idn.toUnicode(firstLabel);
    if (firstLabel.startsWith(ACE_PREFIX) && firstLabel.equals(unicode)) {
      throw new InvalidPunycodeException();
    }
    if (!firstLabel.startsWith(ACE_PREFIX)
        && firstLabel.length() >= 4
        && firstLabel.substring(2).startsWith("--")) {
      throw new DashesInThirdAndFourthException();
    }
  }

  /**
   * Returns name of first matching IDN table for domain label.
   *
   * @throws InvalidIdnDomainLabelException if IDN table or language validation failed
   * @see #validateDomainName(String)
   */
  static String validateDomainNameWithIdnTables(InternetDomainName domainName)
      throws InvalidIdnDomainLabelException {
    Optional<String> idnTableName =
        findValidIdnTableForTld(domainName.parts().get(0), domainName.parent().toString());
    if (!idnTableName.isPresent()) {
      throw new InvalidIdnDomainLabelException();
    }
    return idnTableName.get();
  }

  /** Check if the registrar running the flow has access to the TLD in question. */
  public static void checkAllowedAccessToTld(Set<String> allowedTlds, String tld)
      throws EppException {
    if (!allowedTlds.contains(tld)) {
      throw new DomainFlowUtils.NotAuthorizedForTldException(tld);
    }
  }

  /** Check that the DS data that will be set on a domain is valid. */
  static void validateDsData(Set<DelegationSignerData> dsData) throws EppException {
    if (dsData != null && dsData.size() > MAX_DS_RECORDS_PER_DOMAIN) {
      throw new TooManyDsRecordsException(String.format(
          "A maximum of %s DS records are allowed per domain.", MAX_DS_RECORDS_PER_DOMAIN));
    }
  }

  /** We only allow specifying years in a period. */
  static Period verifyUnitIsYears(Period period) throws EppException {
    if (!checkNotNull(period).getUnit().equals(Period.Unit.YEARS)) {
      throw new BadPeriodUnitException();
    }
    return period;
  }

  /** Verify that no linked resources have disallowed statuses. */
  static void verifyNotInPendingDelete(
      Set<DesignatedContact> contacts,
      ReferenceUnion<ContactResource> registrant,
      Set<ReferenceUnion<HostResource>> nameservers) throws EppException {
    for (DesignatedContact contact : nullToEmpty(contacts)) {
      verifyNotInPendingDelete(contact.getContactId());
    }
    if (registrant != null) {
      verifyNotInPendingDelete(registrant);
    }
    for (ReferenceUnion<HostResource> host : nullToEmpty(nameservers)) {
      verifyNotInPendingDelete(host);
    }
  }

  private static void verifyNotInPendingDelete(
      ReferenceUnion<? extends EppResource> resourceRef) throws EppException {

    EppResource resource = resourceRef.getLinked().get();
    if (resource.getStatusValues().contains(StatusValue.PENDING_DELETE)) {
      throw new LinkedResourceInPendingDeleteProhibitsOperationException(resource.getForeignKey());
    }
  }

  static void validateContactsHaveTypes(Set<DesignatedContact> contacts)
      throws ParameterValuePolicyErrorException {
    for (DesignatedContact contact : nullToEmpty(contacts)) {
      if (contact.getType() == null) {
        throw new MissingContactTypeException();
      }
    }
  }

  /** Return a foreign key for a {@link ReferenceUnion} from memory or datastore as needed. */
  private static String resolveForeignKey(ReferenceUnion<?> ref) {
    return Optional.fromNullable(ref.getForeignKey()).or(ref.getLinked().get().getForeignKey());
  }

  static void validateNameservers(String tld, Set<ReferenceUnion<HostResource>> nameservers)
      throws EppException {
    if (nameservers != null && nameservers.size() > MAX_NAMESERVERS_PER_DOMAIN) {
        throw new TooManyNameserversException(String.format(
            "Only %d nameservers are allowed per domain", MAX_NAMESERVERS_PER_DOMAIN));
    }
    ImmutableSet<String> whitelist = Registry.get(tld).getAllowedFullyQualifiedHostNames();
    if (!whitelist.isEmpty()) {  // Empty whitelists are ignored.
      for (ReferenceUnion<HostResource> nameserver : nameservers) {
        String foreignKey = resolveForeignKey(nameserver);
        if (!whitelist.contains(foreignKey)) {
          throw new NameserverNotAllowedException(foreignKey);
        }
      }
    }
  }

  static void validateNoDuplicateContacts(Set<DesignatedContact> contacts)
      throws ParameterValuePolicyErrorException {
    Set<Type> roles = new HashSet<>();
    for (DesignatedContact contact : nullToEmpty(contacts)) {
      if (!roles.add(contact.getType())) {
        throw new DuplicateContactForRoleException();
      }
    }
  }

  static void validateRequiredContactsPresent(
      ReferenceUnion<ContactResource> registrant, Set<DesignatedContact> contacts)
      throws RequiredParameterMissingException {
    if (registrant == null) {
      throw new MissingRegistrantException();
    }

    Set<Type> roles = new HashSet<>();
    for (DesignatedContact contact : nullToEmpty(contacts)) {
      roles.add(contact.getType());
    }
    if (!roles.contains(Type.ADMIN)) {
      throw new MissingAdminContactException();
    }
    if (!roles.contains(Type.TECH)) {
      throw new MissingTechnicalContactException();
    }
  }

  static void validateRegistrantAllowedOnTld(String tld, ReferenceUnion<ContactResource> registrant)
      throws RegistrantNotAllowedException {
    ImmutableSet<String> whitelist = Registry.get(tld).getAllowedRegistrantContactIds();
    // Empty whitelists are ignored.
    if (!whitelist.isEmpty() && !whitelist.contains(resolveForeignKey(registrant))) {
      throw new RegistrantNotAllowedException(registrant.toString());
    }
  }

  static void verifyNotReserved(
      InternetDomainName domainName, boolean isSunriseApplication) throws EppException {
    if (isReserved(domainName, isSunriseApplication)) {
      throw new DomainReservedException(domainName.toString());
    }
  }

  private static boolean isReserved(InternetDomainName domainName, boolean inSunrise) {
    ReservationType type = getReservationType(domainName);
    return type == ReservationType.FULLY_BLOCKED
        || type == ReservationType.RESERVED_FOR_ANCHOR_TENANT
        || (TYPES_ALLOWED_FOR_CREATE_ONLY_IN_SUNRISE.contains(type) && !inSunrise);
  }

  /** Returns an enum that encodes how and when this name is reserved in the current tld. */
  static ReservationType getReservationType(InternetDomainName domainName) {
    // The TLD should always be the parent of the requested domain name.
    return getReservation(domainName.parts().get(0), domainName.parent().toString());
  }

  /** Verifies that a launch extension's specified phase matches the specified registry's phase. */
  static void verifyLaunchPhase(
      String tld, LaunchExtension launchExtension, DateTime now) throws EppException {
    if (!Objects.equals(
        Registry.get(tld).getTldState(now),
        LAUNCH_PHASE_TO_TLD_STATE.get(launchExtension.getPhase()))) {
      // No launch operations are allowed during the quiet period or predelegation.
      throw new LaunchPhaseMismatchException();
    }
  }

  /**
   * Verifies that a launch extension's application id refers to an application with the same
   * domain name as the one specified in the launch command.
   */
  static void verifyLaunchApplicationIdMatchesDomain(
      SingleResourceCommand command, DomainBase existingResource) throws EppException {
    if (!Objects.equals(command.getTargetId(), existingResource.getFullyQualifiedDomainName())) {
      throw new ApplicationDomainNameMismatchException();
    }
  }

  /**
   * Verifies that a domain name is allowed to be delegated to the given client id. The only case
   * where it would not be allowed is if domain name is premium, and premium names are blocked by
   * this registrar.
   */
  static void verifyPremiumNameIsNotBlocked(String domainName, String tld, String clientId)
      throws EppException {
    if (Registry.get(tld).isPremiumName(domainName)) {
      // NB: The load of the Registar object is transactionless, which means that it should hit
      // memcache most of the time.
      if (Registrar.loadByClientId(clientId).getBlockPremiumNames()) {
        throw new PremiumNameBlockedException();
      }
    }
  }

  /**
   * Helper to call {@link CreateOrUpdate#cloneAndLinkReferences} and convert exceptions to
   * EppExceptions, since this is needed in several places.
   */
  static <T extends CreateOrUpdate<T>> T cloneAndLinkReferences(T command, DateTime now)
      throws EppException {
    try {
      return command.cloneAndLinkReferences(now);
    } catch (InvalidReferenceException e) {
      throw new LinkedResourceDoesNotExistException(e.getType(), e.getForeignKey());
    }
  }

  /**
   * Fills in a builder with the data needed for an autorenew billing event for this domain. This
   * does not copy over the id of the current autorenew billing event.
   */
  static BillingEvent.Recurring.Builder newAutorenewBillingEvent(DomainResource domain) {
    return new BillingEvent.Recurring.Builder()
        .setReason(Reason.RENEW)
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
        .setTargetId(domain.getFullyQualifiedDomainName())
        .setClientId(domain.getCurrentSponsorClientId())
        .setEventTime(domain.getRegistrationExpirationTime());
  }

  /**
   * Fills in a builder with the data needed for an autorenew poll message for this domain. This
   * does not copy over the id of the current autorenew poll message.
   */
  static PollMessage.Autorenew.Builder newAutorenewPollMessage(DomainResource domain) {
    return new PollMessage.Autorenew.Builder()
        .setTargetId(domain.getFullyQualifiedDomainName())
        .setClientId(domain.getCurrentSponsorClientId())
        .setEventTime(domain.getRegistrationExpirationTime())
        .setMsg("Domain was auto-renewed.");
  }

  /**
   * Re-saves the current autorenew billing event and poll message with a new end time. This may end
   * up deleting the poll message (if closing the message interval) or recreating it (if opening the
   * message interval).
   */
  @SuppressWarnings("unchecked")
  static void updateAutorenewRecurrenceEndTime(DomainResource domain, DateTime newEndTime) {
    Optional<PollMessage.Autorenew> autorenewPollMessage =
        Optional.fromNullable(domain.getAutorenewPollMessage().get());

    // Construct an updated autorenew poll message. If the autorenew poll message no longer exists,
    // create a new one at the same id. This can happen if a transfer was requested on a domain
    // where all autorenew poll messages had already been delivered (this would cause the poll
    // message to be deleted), and then subsequently the transfer was canceled, rejected, or deleted
    // (which would cause the poll message to be recreated here).
    Key<PollMessage.Autorenew> existingAutorenewKey = domain.getAutorenewPollMessage().key();
    PollMessage.Autorenew updatedAutorenewPollMessage = autorenewPollMessage.isPresent()
        ? autorenewPollMessage.get().asBuilder().setAutorenewEndTime(newEndTime).build()
        : newAutorenewPollMessage(domain)
            .setId(existingAutorenewKey.getId())
            .setAutorenewEndTime(newEndTime)
            .setParentKey(existingAutorenewKey.<HistoryEntry>getParent())
            .build();

    // If the resultant autorenew poll message would have no poll messages to deliver, then just
    // delete it. Otherwise save it with the new end time.
    if (isAtOrAfter(updatedAutorenewPollMessage.getEventTime(), newEndTime)) {
      if (autorenewPollMessage.isPresent()) {
        ofy().delete().entity(autorenewPollMessage.get());
      }
    } else {
      ofy().save().entity(updatedAutorenewPollMessage);
    }

    ofy().save().entity(domain.getAutorenewBillingEvent().get().asBuilder()
        .setRecurrenceEndTime(newEndTime)
        .build());
  }

  public static SignedMark verifySignedMarks(
      ImmutableList<AbstractSignedMark> signedMarks, String domainLabel, DateTime now)
          throws EppException {
    if (signedMarks.size() > 1) {
      throw new TooManySignedMarksException();
    }
    if (!(signedMarks.get(0) instanceof EncodedSignedMark)) {
      throw new SignedMarksMustBeEncodedException();
    }
    return verifyEncodedSignedMark((EncodedSignedMark) signedMarks.get(0), domainLabel, now);
  }

  public static SignedMark verifyEncodedSignedMark(
      EncodedSignedMark encodedSignedMark, String domainLabel, DateTime now) throws EppException {
    if (!encodedSignedMark.getEncoding().equals("base64")) {
      throw new Base64RequiredForEncodedSignedMarksException();
    }
    byte[] signedMarkData;
    try {
      signedMarkData = encodedSignedMark.getBytes();
    } catch (IllegalStateException e) {
      throw new SignedMarkEncodingErrorException();
    }

    SignedMark signedMark;
    try {
      signedMark = unmarshal(signedMarkData);
    } catch (EppException e) {
      throw new SignedMarkParsingErrorException();
    }

    if (SignedMarkRevocationList.get().isSmdRevoked(signedMark.getId(), now)) {
      throw new SignedMarkRevokedErrorException();
    }

    try {
      TmchXmlSignature.verify(signedMarkData);
    } catch (CertificateExpiredException e) {
      throw new SignedMarkCertificateExpiredException();
    } catch (CertificateNotYetValidException e) {
      throw new SignedMarkCertificateNotYetValidException();
    } catch (CertificateRevokedException e) {
      throw new SignedMarkCertificateRevokedException();
    } catch (CertificateSignatureException e) {
      throw new SignedMarkCertificateSignatureException();
    } catch (SignatureException | XMLSignatureException e) {
      throw new SignedMarkSignatureException();
    } catch (GeneralSecurityException e) {
      throw new SignedMarkCertificateInvalidException();
    } catch (IOException
        | MarshalException
        | SAXException
        | ParserConfigurationException e) {
      throw new SignedMarkParsingErrorException();
    }

    if (!(isAtOrAfter(now, signedMark.getCreationTime())
          && now.isBefore(signedMark.getExpirationTime())
          && containsMatchingLabel(signedMark.getMark(), domainLabel))) {
      throw new NoMarksFoundMatchingDomainException();
    }
    return signedMark;
  }

  /** Returns true if the mark contains a valid claim that matches the label. */
  static boolean containsMatchingLabel(Mark mark, String label) {
    for (Trademark trademark : mark.getTrademarks()) {
      if (trademark.getLabels().contains(label)) {
        return true;
      }
    }
    for (ProtectedMark protectedMark
        : concat(mark.getTreatyOrStatuteMarks(), mark.getCourtMarks())) {
      if (protectedMark.getLabels().contains(label)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Validates a {@link BaseFeeRequest} and sets the appropriate fields on a {@link BaseFeeResponse}
   * builder.
   */
  static void handleFeeRequest(
      BaseFeeRequest feeRequest,
      BaseFeeResponse.Builder<?, ?> builder,
      String domainName,
      String tld,
      DateTime now) throws EppException {
    InternetDomainName domain = InternetDomainName.from(domainName);
    FeeCommandDescriptor feeCommand = feeRequest.getCommand();
    Registry registry = Registry.get(tld);
    int years = verifyUnitIsYears(feeRequest.getPeriod()).getValue();
    boolean isSunrise = registry.getTldState(now).equals(TldState.SUNRISE);
    boolean isNameCollisionInSunrise =
        isSunrise && getReservationType(domain) == ReservationType.NAME_COLLISION;

    if (feeCommand.getPhase() != null || feeCommand.getSubphase() != null) {
      throw new FeeChecksDontSupportPhasesException();
    }
    if (feeRequest.getCurrency() != null
        && !feeRequest.getCurrency().equals(registry.getCurrency())) {
      throw new CurrencyUnitMismatchException();
    }

    builder
        .setCommand(feeCommand)
        .setCurrency(registry.getCurrency())
        .setPeriod(feeRequest.getPeriod())
        // Choose from four classes: premium, premium-collision, collision, or null (standard case).
        .setClass(emptyToNull(Joiner.on('-').skipNulls().join(
            registry.isPremiumName(domainName) ? "premium" : null,
            isNameCollisionInSunrise ? "collision" : null)));

    switch (feeCommand.getCommand()) {
      case UNKNOWN:
        throw new UnknownFeeCommandException(feeCommand.getUnparsedCommandName());
      case CREATE:
        if (isReserved(domain, isSunrise)) {  // Don't return a create price for reserved names.
          builder.setClass("reserved");  // Override whatever class we've set above.
        } else {
          builder.setFee(
              Fee.create(registry.getDomainCreateCost(domainName, years).getAmount(), "create"));
        }
        break;
      case RESTORE:
        if (years != 1) {
          throw new RestoresAreAlwaysForOneYearException();
        }
        // Restores have a "renew" and a "restore" fee.
        builder.setFee(
            Fee.create(registry.getDomainRenewCost(domainName, years, now).getAmount(), "renew"),
            Fee.create(registry.getStandardRestoreCost().getAmount(), "restore"));
        break;
      default:
        // Anything else (transfer|renew) will have a "renew" fee.
        builder.setFee(
            Fee.create(registry.getDomainRenewCost(domainName, years, now).getAmount(), "renew"));
    }
  }

  static void validateFeeChallenge(
      String domainName, String tld,
      final BaseFeeCommand feeCommand,
      Money cost, Money... otherCosts) throws EppException {
    Registry registry = Registry.get(tld);
    if (registry.getPremiumPriceAckRequired()
        && registry.isPremiumName(domainName)
        && feeCommand == null) {
      throw new FeesRequiredForPremiumNameException();
    }
    if (feeCommand == null) {
      return;
    }
    List<Fee> fees = feeCommand.getFees();
    // The schema guarantees that at least one fee will be present.
    checkState(!fees.isEmpty());
    BigDecimal total = BigDecimal.ZERO;
    for (Fee fee : fees) {
      if (!fee.hasDefaultAttributes()) {
        throw new UnsupportedFeeAttributeException();
      }
      total = total.add(fee.getCost());
    }

    Money feeTotal = null;
    try {
      feeTotal = Money.of(feeCommand.getCurrency(), total);
    } catch (ArithmeticException e) {
      throw new CurrencyValueScaleException();
    }

    Money costTotal = cost;
    for (Money otherCost : otherCosts) {
      costTotal = costTotal.plus(otherCost);
    }

    if (!feeTotal.getCurrencyUnit().equals(costTotal.getCurrencyUnit())) {
      throw new CurrencyUnitMismatchException();
    }
    if (!feeTotal.equals(costTotal)) {
      throw new FeesMismatchException();
    }
  }

  /** Encoded signed marks must use base64 encoding. */
  static class Base64RequiredForEncodedSignedMarksException
      extends ParameterValuePolicyErrorException {
    public Base64RequiredForEncodedSignedMarksException() {
      super("Encoded signed marks must use base64 encoding");
    }
  }

  /** Resource linked to this domain does not exist. */
  static class LinkedResourceDoesNotExistException extends ObjectDoesNotExistException {
    public LinkedResourceDoesNotExistException(Class<?> type, String resourceId) {
      super(type, resourceId);
    }
  }

  /** Linked resource in pending delete prohibits operation. */
  static class LinkedResourceInPendingDeleteProhibitsOperationException
      extends StatusProhibitsOperationException {
    public LinkedResourceInPendingDeleteProhibitsOperationException(String resourceId) {
      super(String.format(
          "Linked resource in pending delete prohibits operation: %s",
          resourceId));
    }
  }

  /** Domain names can only contain a-z, 0-9, '.' and '-'. */
  static class BadDomainNameCharacterException extends ParameterValuePolicyErrorException {
    public BadDomainNameCharacterException() {
      super("Domain names can only contain a-z, 0-9, '.' and '-'");
    }
  }

  /** Non-IDN domain names cannot contain hyphens in the third or fourth position. */
  static class DashesInThirdAndFourthException extends ParameterValuePolicyErrorException {
    public DashesInThirdAndFourthException() {
      super("Non-IDN domain names cannot contain dashes in the third or fourth position");
    }
  }

  /** Domain labels cannot begin with a dash. */
  static class LeadingDashException extends ParameterValuePolicyErrorException {
    public LeadingDashException() {
      super("Domain labels cannot begin with a dash");
    }
  }

  /** Domain labels cannot end with a dash. */
  static class TrailingDashException extends ParameterValuePolicyErrorException {
    public TrailingDashException() {
      super("Domain labels cannot end with a dash");
    }
  }

  /** Domain labels cannot be longer than 63 characters. */
  static class DomainLabelTooLongException extends ParameterValuePolicyErrorException {
    public DomainLabelTooLongException() {
      super("Domain labels cannot be longer than 63 characters");
    }
  }

  /** No part of a domain name can be empty. */
  static class EmptyDomainNamePartException extends ParameterValuePolicyErrorException {
    public EmptyDomainNamePartException() {
      super("No part of a domain name can be empty");
    }
  }

  /** Domain name starts with xn-- but is not a valid IDN. */
  static class InvalidPunycodeException extends ParameterValuePolicyErrorException {
    public InvalidPunycodeException() {
      super("Domain name starts with xn-- but is not a valid IDN");
    }
  }

  /** Periods for domain registrations must be specified in years. */
  static class BadPeriodUnitException extends ParameterValuePolicyErrorException {
    public BadPeriodUnitException() {
      super("Periods for domain registrations must be specified in years");
    }
  }

  /** Missing type attribute for contact. */
  static class MissingContactTypeException extends ParameterValuePolicyErrorException {
    public MissingContactTypeException() {
      super("Missing type attribute for contact");
    }
  }

  /** More than one contact for a given role is not allowed. */
  static class DuplicateContactForRoleException extends ParameterValuePolicyErrorException {
    public DuplicateContactForRoleException() {
      super("More than one contact for a given role is not allowed");
    }
  }

  /** Declared launch extension phase does not match the current registry phase. */
  static class LaunchPhaseMismatchException extends ParameterValuePolicyErrorException {
    public LaunchPhaseMismatchException() {
      super("Declared launch extension phase does not match the current registry phase");
    }
  }

  /** Application referenced does not match specified domain name. */
  static class ApplicationDomainNameMismatchException extends ParameterValuePolicyErrorException {
    public ApplicationDomainNameMismatchException() {
      super("Application referenced does not match specified domain name");
    }
  }

  /** Too many DS records set on a domain. */
  static class TooManyDsRecordsException extends ParameterValuePolicyErrorException {
    public TooManyDsRecordsException(String message) {
      super(message);
    }
  }

  /** Domain name is under tld which doesn't exist. */
  static class TldDoesNotExistException extends ParameterValueRangeErrorException {
    public TldDoesNotExistException(String tld) {
      super(String.format("Domain name is under tld %s which doesn't exist", tld));
    }
  }

  /** Domain label is not allowed by IDN table. */
  static class InvalidIdnDomainLabelException extends ParameterValueRangeErrorException {
    public InvalidIdnDomainLabelException() {
      super("Domain label is not allowed by IDN table");
    }
  }

  /** Registrant is required. */
  static class MissingRegistrantException extends RequiredParameterMissingException {
    public MissingRegistrantException() {
      super("Registrant is required");
    }
  }

  /** Admin contact is required. */
  static class MissingAdminContactException extends RequiredParameterMissingException {
    public MissingAdminContactException() {
      super("Admin contact is required");
    }
  }

  /** Technical contact is required. */
  static class MissingTechnicalContactException extends RequiredParameterMissingException {
    public MissingTechnicalContactException() {
      super("Technical contact is required");
    }
  }

  /** Too many nameservers set on this domain. */
  static class TooManyNameserversException extends ParameterValuePolicyErrorException {
    public TooManyNameserversException(String message) {
      super(message);
    }
  }

  /** Domain name must have exactly one part above the tld. */
  static class BadDomainNamePartsCountException extends ParameterValueSyntaxErrorException {
    public BadDomainNamePartsCountException() {
      super("Domain name must have exactly one part above the tld");
    }
  }

  /** Signed mark data is improperly encoded. */
  static class SignedMarkEncodingErrorException extends ParameterValueSyntaxErrorException {
    public SignedMarkEncodingErrorException() {
      super("Signed mark data is improperly encoded");
    }
  }

  /** Error while parsing encoded signed mark data. */
  static class SignedMarkParsingErrorException extends ParameterValueSyntaxErrorException {
    public SignedMarkParsingErrorException() {
      super("Error while parsing encoded signed mark data");
    }
  }

  /** Invalid signature on a signed mark. */
  static class SignedMarkSignatureException extends ParameterValuePolicyErrorException {
    public SignedMarkSignatureException() {
      super("Signed mark signature is invalid");
    }
  }

  /** Invalid signature on a signed mark. */
  static class SignedMarkCertificateSignatureException extends ParameterValuePolicyErrorException {
    public SignedMarkCertificateSignatureException() {
      super("Signed mark certificate not signed by ICANN");
    }
  }

  /** Certificate used in signed mark signature was revoked by ICANN. */
  static class SignedMarkCertificateRevokedException extends ParameterValuePolicyErrorException {
    public SignedMarkCertificateRevokedException() {
      super("Signed mark certificate was revoked");
    }
  }

  /** Certificate used in signed mark signature has expired. */
  static class SignedMarkCertificateExpiredException extends ParameterValuePolicyErrorException {
    public SignedMarkCertificateExpiredException() {
      super("Signed mark certificate has expired");
    }
  }

  /** Certificate used in signed mark signature has expired. */
  static class SignedMarkCertificateNotYetValidException
      extends ParameterValuePolicyErrorException {
    public SignedMarkCertificateNotYetValidException() {
      super("Signed mark certificate not yet valid");
    }
  }

  /** Certificate parsing error, or possibly a bad provider or algorithm. */
  static class SignedMarkCertificateInvalidException extends ParameterValuePolicyErrorException {
    public SignedMarkCertificateInvalidException() {
      super("Signed mark certificate is invalid");
    }
  }

  /** Signed mark data is revoked. */
  static class SignedMarkRevokedErrorException extends ParameterValuePolicyErrorException {
    public SignedMarkRevokedErrorException() {
      super("SMD has been revoked");
    }
  }

  /** Only one signed mark is allowed per application. */
  static class TooManySignedMarksException extends ParameterValuePolicyErrorException {
    public TooManySignedMarksException() {
      super("Only one signed mark is allowed per application");
    }
  }

  /** Signed marks must be encoded. */
  static class SignedMarksMustBeEncodedException extends ParameterValuePolicyErrorException {
    public SignedMarksMustBeEncodedException() {
      super("Signed marks must be encoded");
    }
  }

  /** The provided mark does not match the desired domain label. */
  static class NoMarksFoundMatchingDomainException extends RequiredParameterMissingException {
    public NoMarksFoundMatchingDomainException() {
      super("The provided mark does not match the desired domain label");
    }
  }

  /** Unknown fee command name. */
  static class UnknownFeeCommandException extends ParameterValuePolicyErrorException {
    UnknownFeeCommandException(String commandName) {
      super("Unknown fee command: " + commandName);
    }
  }

  /** Fee checks for command phases and subphases are not supported. */
  static class FeeChecksDontSupportPhasesException extends ParameterValuePolicyErrorException {
    FeeChecksDontSupportPhasesException() {
      super("Fee checks for command phases and subphases are not supported");
    }
  }

  /** The requested fees cannot be provided in the requested currency. */
  static class CurrencyUnitMismatchException extends ParameterValuePolicyErrorException {
    CurrencyUnitMismatchException() {
      super("The requested fees cannot be provided in the requested currency");
    }
  }

  /** The requested fee is expressed in a scale that is invalid for the given currency. */
  static class CurrencyValueScaleException extends ParameterValueSyntaxErrorException {
    CurrencyValueScaleException() {
      super("The requested fee is expressed in a scale that is invalid for the given currency");
    }
  }

  /** Fees must be explicitly acknowledged when performing any operations on a premium name. */
  static class FeesRequiredForPremiumNameException extends RequiredParameterMissingException {
    FeesRequiredForPremiumNameException() {
      super("Fees must be explicitly acknowledged when performing any operations on a premium"
          + " name");
    }
  }

  /** The 'grace-period', 'applied' and 'refundable' fields are disallowed by server policy. */
  static class UnsupportedFeeAttributeException extends UnimplementedOptionException {
    UnsupportedFeeAttributeException() {
      super("The 'grace-period', 'refundable' and 'applied' attributes are disallowed by server "
          + "policy");
    }
  }

  /** Restores always renew a domain for one year. */
  static class RestoresAreAlwaysForOneYearException extends ParameterValuePolicyErrorException {
    RestoresAreAlwaysForOneYearException() {
      super("Restores always renew a domain for one year");
    }
  }

  /** Requested domain is reserved. */
  static class DomainReservedException extends StatusProhibitsOperationException {
    public DomainReservedException(String domainName) {
      super(String.format("%s is a reserved domain", domainName));
    }
  }

  /**
   * The requested domain name is on the premium price list, and this registrar has blocked premium
   * registrations.
   */
  static class PremiumNameBlockedException extends StatusProhibitsOperationException {
    public PremiumNameBlockedException() {
      super("The requested domain name is on the premium price list, "
          + "and this registrar has blocked premium registrations");
    }
  }

  /** The fees passed in the transform command do not match the fees that will be charged. */
  static class FeesMismatchException extends ParameterValueRangeErrorException {
    public FeesMismatchException() {
      super("The fees passed in the transform command do not match the fees that will be charged");
    }
  }

  /** Registrar is not authorized to access this TLD. */
  public static class NotAuthorizedForTldException extends AuthorizationErrorException {
    public NotAuthorizedForTldException(String tld) {
      super("Registrar is not authorized to access the TLD " + tld);
    }
  }

  /** Registrant is not whitelisted for this TLD. */
  public static class RegistrantNotAllowedException extends StatusProhibitsOperationException {
    public RegistrantNotAllowedException(String contactId) {
      super(String.format("Registrant with id %s is not whitelisted for this TLD", contactId));
    }
  }

  /** Nameserver is not whitelisted for this TLD. */
  public static class NameserverNotAllowedException extends StatusProhibitsOperationException {
    public NameserverNotAllowedException(String fullyQualifiedHostName) {
      super(String.format("Nameserver %s is not whitelisted for this TLD", fullyQualifiedHostName));
    }
  }
}