package org.mitre.synthea.world.concepts;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.mitre.synthea.export.JSONSkip;
import org.mitre.synthea.world.agents.PayerManager;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.HealthRecord.Encounter;
import org.mitre.synthea.world.concepts.HealthRecord.Entry;
import org.mitre.synthea.world.concepts.HealthRecord.Medication;
import org.mitre.synthea.world.concepts.healthinsurance.CoverageRecord.PlanRecord;
import org.mitre.synthea.world.concepts.healthinsurance.InsurancePlan;

public class Claim implements Serializable {
  private static final long serialVersionUID = -3565704321813987656L;
  public static final BigDecimal ZERO_CENTS = BigDecimal.ZERO.setScale(2);

  public static class ClaimCost {
    /** total cost of the entry. */
    public BigDecimal cost = ZERO_CENTS;
    /** copay paid by patient. */
    public BigDecimal copayPaidByPatient = ZERO_CENTS;
    /** deductible paid by patient. */
    public BigDecimal deductiblePaidByPatient = ZERO_CENTS;
    /** amount the charge was decreased by payer adjustment. */
    public BigDecimal adjustment = ZERO_CENTS;
    /** coinsurance paid by payer. */
    public BigDecimal coinsurancePaidByPayer = ZERO_CENTS;
    /** otherwise paid by payer. */
    public BigDecimal paidByPayer = ZERO_CENTS;
    /** otherwise paid by secondary payer. */
    public BigDecimal paidBySecondaryPayer = ZERO_CENTS;
    /** otherwise paid by patient out of pocket. */
    public BigDecimal patientOutOfPocket = ZERO_CENTS;

    /**
     * Create a new instance with all costs set to zero.
     */
    public ClaimCost() {
    }

    /**
     * Reset all claim costs to zero.
     */
    public void reset() {
      cost = ZERO_CENTS;
      copayPaidByPatient = ZERO_CENTS;
      deductiblePaidByPatient = ZERO_CENTS;
      adjustment = ZERO_CENTS;
      coinsurancePaidByPayer = ZERO_CENTS;
      paidByPayer = ZERO_CENTS;
      paidBySecondaryPayer = ZERO_CENTS;
      patientOutOfPocket = ZERO_CENTS;
    }

    /**
     * Create a new instance with the same cost values as the supplied instance.
     * @param other the instance to copy costs from
     */
    public ClaimCost(ClaimCost other) {
      this.cost = other.cost;
      this.copayPaidByPatient = other.copayPaidByPatient;
      this.deductiblePaidByPatient = other.deductiblePaidByPatient;
      this.adjustment = other.adjustment;
      this.coinsurancePaidByPayer = other.coinsurancePaidByPayer;
      this.paidByPayer = other.paidByPayer;
      this.paidBySecondaryPayer = other.paidBySecondaryPayer;
      this.patientOutOfPocket = other.patientOutOfPocket;
    }

    /**
     * Add the costs from the other entry to this one.
     * @param other the other claim entry.
     */
    public void addCosts(ClaimCost other) {
      this.cost = this.cost.add(other.cost);
      this.copayPaidByPatient = this.copayPaidByPatient.add(other.copayPaidByPatient);
      this.deductiblePaidByPatient
          = this.deductiblePaidByPatient.add(other.deductiblePaidByPatient);
      this.adjustment = this.adjustment.add(other.adjustment);
      this.coinsurancePaidByPayer = this.coinsurancePaidByPayer.add(other.coinsurancePaidByPayer);
      this.paidByPayer = this.paidByPayer.add(other.paidByPayer);
      this.paidBySecondaryPayer = this.paidBySecondaryPayer.add(other.paidBySecondaryPayer);
      this.patientOutOfPocket = this.patientOutOfPocket.add(other.patientOutOfPocket);
    }

    /**
     * Returns the amount of coinsurance paid by the patient, either via secondary insurance or out
     * of pocket.
     * @return the amount of coinsurance paid
     */
    public BigDecimal getCoinsurancePaid() {
      if (this.paidBySecondaryPayer.compareTo(Claim.ZERO_CENTS) > 0) {
        return this.paidBySecondaryPayer;
      } else if (this.coinsurancePaidByPayer.compareTo(Claim.ZERO_CENTS) > 0) {
        return this.patientOutOfPocket;
      }
      return Claim.ZERO_CENTS;
    }

    /**
     * Returns the total cost of the Claim, including immunizations/procedures tied to the
     * encounter.
     */
    public BigDecimal getTotalClaimCost() {
      return cost;
    }

    public BigDecimal getCoveredCost() {
      return coinsurancePaidByPayer.add(paidByPayer);
    }

    public BigDecimal getDeductiblePaid() {
      return deductiblePaidByPatient;
    }

    public BigDecimal getCopayPaid() {
      return copayPaidByPatient;
    }

    public BigDecimal getPatientCost() {
      return patientOutOfPocket.add(copayPaidByPatient).add(deductiblePaidByPatient);
    }
  }

  public InsurancePlan plan;
  public InsurancePlan secondaryPlan;

  public class ClaimEntry extends ClaimCost implements Serializable {
    private static final long serialVersionUID = 1871121895630816723L;
    @JSONSkip
    public Entry entry;

    public ClaimEntry(Entry entry) {
      this.entry = entry;
    }

    /**
     * Assign costs for this ClaimEntry.
     * @param planRecord  The planrecord to check patient costs from.
     */
    private void assignCosts(PlanRecord planRecord) {
      reset();
      this.cost = this.entry.getCost();
      BigDecimal remainingBalance = this.cost;

      if (!plan.coversService(this.entry)) {
        plan.incrementUncoveredEntries(this.entry);
        // Payer does not cover care
        this.patientOutOfPocket = remainingBalance;
        return;
      }

      plan.incrementCoveredEntries(this.entry);
      // Apply copay to Encounters and Medication claims only
      if ((this.entry instanceof HealthRecord.Encounter)
          || (this.entry instanceof HealthRecord.Medication)) {
        this.copayPaidByPatient = plan.determineCopay(this.entry);
        if (this.copayPaidByPatient.compareTo(remainingBalance) > 0) {
          this.copayPaidByPatient = remainingBalance;
        }
        remainingBalance = remainingBalance.subtract(this.copayPaidByPatient);
      }
      // Check if the patient has remaining deductible
      if (remainingBalance.compareTo(Claim.ZERO_CENTS) > 0 && planRecord.remainingDeductible
              .compareTo(Claim.ZERO_CENTS) > 0) {
        if (planRecord.remainingDeductible.compareTo(remainingBalance) >= 0) {
          this.deductiblePaidByPatient = remainingBalance;
        } else {
          this.deductiblePaidByPatient = planRecord.remainingDeductible;
        }
        remainingBalance = remainingBalance.subtract(this.deductiblePaidByPatient);
        planRecord.remainingDeductible = planRecord.remainingDeductible
            .subtract(this.deductiblePaidByPatient);
      }
      if (remainingBalance.compareTo(Claim.ZERO_CENTS) > 0) {
        // Check if the payer has an adjustment
        BigDecimal adjustment = plan.adjustClaim(this, person);
        remainingBalance = remainingBalance.subtract(adjustment);
      }
      if (remainingBalance.compareTo(Claim.ZERO_CENTS) > 0) {
        // Check if the patient has coinsurance
        BigDecimal patientCoinsurance = plan.getPatientCoinsurance();
        if (patientCoinsurance.compareTo(Claim.ZERO_CENTS) > 0) {
          BigDecimal payerCoinsurance = BigDecimal.ONE.subtract(plan.getPatientCoinsurance());
          // Payer covers some
          this.coinsurancePaidByPayer = payerCoinsurance.multiply(remainingBalance)
                  .setScale(2, RoundingMode.HALF_EVEN);
          remainingBalance = remainingBalance.subtract(this.coinsurancePaidByPayer);
        } else {
          // Payer covers all
          this.paidByPayer = remainingBalance;
          remainingBalance = remainingBalance.subtract(this.paidByPayer);
        }
      }
      if (remainingBalance.compareTo(Claim.ZERO_CENTS) > 0) {
        // If secondary insurance, payer covers remainder, not patient.
        if (!secondaryPlan.isNoInsurance()) {
          this.paidBySecondaryPayer = remainingBalance;
          remainingBalance = remainingBalance.subtract(this.paidBySecondaryPayer);
        }
      }
      if (remainingBalance.compareTo(Claim.ZERO_CENTS) > 0) {
        // Patient amount
        this.patientOutOfPocket = remainingBalance;
        remainingBalance = remainingBalance.subtract(this.patientOutOfPocket);
      }
    }
  }

  @JSONSkip
  public Person person;
  public ClaimEntry mainEntry;
  public List<ClaimEntry> items;
  public ClaimEntry totals;
  public final UUID uuid;

  /**
   * Constructor of a Claim for an Entry.
   */
  public Claim(Entry entry, Person person) {
    // Set the Entry.
    if ((entry instanceof Encounter) || (entry instanceof Medication)) {
      this.mainEntry = new ClaimEntry(entry);
    } else {
      throw new RuntimeException(
          "A Claim can only be made with entry types Encounter or Medication.");
    }
    // Set the Person.
    this.person = person;
    // Set the Payer(s)
    PlanRecord planRecord = this.person.coverage.getPlanRecordAtTime(entry.start);
    if (planRecord != null) {
      this.plan = planRecord.plan;
      this.secondaryPlan = planRecord.secondaryPlan;
    }
    if (this.plan == null) {
      // This can rarely occur when an death certification encounter
      // occurs on the birthday or immediately afterwards before a new
      // insurance plan is selected.
      this.plan = this.person.coverage.getLastInsurancePlan();
      if (this.plan == null) {
        this.plan = PayerManager.getNoInsurancePlan();
      }
      this.secondaryPlan = PayerManager.getNoInsurancePlan();
    }
    this.items = new ArrayList<ClaimEntry>();
    this.totals = new ClaimEntry(entry);
    this.uuid = this.person.randUUID();
  }

  /**
   * Adds non-explicit costs to the Claim. (Procedures/Immunizations/etc).
   */
  public void addLineItem(Entry entry) {
    ClaimEntry claimEntry = new ClaimEntry(entry);
    this.items.add(claimEntry);
  }

  /**
   * Assign costs between the payer and patient for all ClaimEntries in this claim.
   */
  public void assignCosts() {
    PlanRecord planRecord = person.coverage.getPlanRecordAtTime(mainEntry.entry.start);
    if (planRecord == null) {
      planRecord = person.coverage.getLastPlanRecord();
      if (planRecord == null) {
        person.coverage.setPlanToNoInsurance(mainEntry.entry.start);
        planRecord = person.coverage.getLastPlanRecord();
      }
    }
    mainEntry.assignCosts(planRecord);
    totals = new ClaimEntry(mainEntry.entry);
    totals.addCosts(mainEntry);
    for (ClaimEntry item : items) {
      item.assignCosts(planRecord);
      totals.addCosts(item);
    }

    planRecord.incrementPatientExpenses(getTotalPatientCost());
    planRecord.incrementPrimaryCoverage(getTotalCoveredCost());
    planRecord.incrementSecondaryCoverage(getTotalPaidBySecondaryPayer());
  }

  /**
   * Returns the total cost of the Claim, including immunizations/procedures tied to the encounter.
   */
  public BigDecimal getTotalClaimCost() {
    return this.totals.getTotalClaimCost();
  }

  /**
   * Returns the total cost that the Payer covered for this claim.
   */
  public BigDecimal getTotalCoveredCost() {
    return this.totals.getCoveredCost();
  }

  public BigDecimal getTotalDeductiblePaid() {
    return this.totals.getDeductiblePaid();
  }

  public BigDecimal getTotalCopayPaid() {
    return this.totals.getCopayPaid();
  }

  public BigDecimal getTotalPaidBySecondaryPayer() {
    return this.totals.paidBySecondaryPayer;
  }

  public BigDecimal getTotalAdjustment() {
    return this.totals.adjustment;
  }

  /**
   * Returns the amount of coinsurance paid by the patient, either via secondary insurance or out
   * of pocket.
   * @return the amount of coinsurance paid
   */
  public BigDecimal getTotalCoinsurancePaid() {
    return this.totals.getCoinsurancePaid();
  }

  /**
   * Returns the total cost to the patient, including copay, coinsurance, and deductible.
   */
  public BigDecimal getTotalPatientCost() {
    return this.totals.getPatientCost();
  }
}