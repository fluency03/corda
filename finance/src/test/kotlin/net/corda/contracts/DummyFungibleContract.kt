package net.corda.contracts.asset

import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.newSecureRandom
import net.corda.core.crypto.toBase58String
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.internal.Emoji
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.schemas.SampleCashSchemaV1
import net.corda.schemas.SampleCashSchemaV2
import net.corda.schemas.SampleCashSchemaV3
import java.security.PublicKey
import java.util.*

class DummyFungibleContract : OnLedgerAsset<Currency, DummyFungibleContract.Commands, DummyFungibleContract.State>() {
    override val legalContractReference: SecureHash = SecureHash.sha256("https://www.big-book-of-banking-law.gov/cash-claims.html")

    override fun extractCommands(commands: Collection<AuthenticatedObject<CommandData>>): List<AuthenticatedObject<DummyFungibleContract.Commands>>
            = commands.select<DummyFungibleContract.Commands>()

    data class State(
            override val amount: Amount<Issued<Currency>>,

            override val owner: AbstractParty
    ) : FungibleAsset<Currency>, QueryableState {
        constructor(deposit: PartyAndReference, amount: Amount<Currency>, owner: AbstractParty)
                : this(Amount(amount.quantity, Issued(deposit, amount.token)), owner)

        override val exitKeys = setOf(owner.owningKey, amount.token.issuer.party.owningKey)
        override val contract = CASH_PROGRAM_ID
        override val participants = listOf(owner)

        override fun move(newAmount: Amount<Issued<Currency>>, newOwner: AbstractParty): FungibleAsset<Currency>
                = copy(amount = amount.copy(newAmount.quantity), owner = newOwner)

        override fun toString() = "${Emoji.bagOfCash}Cash($amount at ${amount.token.issuer} owned by $owner)"

        override fun withNewOwner(newOwner: AbstractParty) = Pair(Commands.Move(), copy(owner = newOwner))

        /** Object Relational Mapping support. */
        override fun generateMappedObject(schema: MappedSchema): PersistentState {
            return when (schema) {
                is SampleCashSchemaV1 -> SampleCashSchemaV1.PersistentCashState(
                        owner = this.owner.owningKey.toBase58String(),
                        pennies = this.amount.quantity,
                        currency = this.amount.token.product.currencyCode,
                        issuerParty = this.amount.token.issuer.party.owningKey.toBase58String(),
                        issuerRef = this.amount.token.issuer.reference.bytes
                )
                is SampleCashSchemaV2 -> SampleCashSchemaV2.PersistentCashState(
                        _participants = this.participants.toSet(),
                        _owner = this.owner,
                        _quantity = this.amount.quantity,
                        currency = this.amount.token.product.currencyCode,
                        _issuerParty = this.amount.token.issuer.party,
                        _issuerRef = this.amount.token.issuer.reference.bytes
                )
                is SampleCashSchemaV3 -> SampleCashSchemaV3.PersistentCashState(
                        _participants = this.participants.toSet(),
                        _owner = this.owner,
                        _quantity = this.amount.quantity,
                        _currency = this.amount.token.product.currencyCode,
                        _issuerParty = this.amount.token.issuer.party,
                        _issuerRef = this.amount.token.issuer.reference.bytes
                )
                else -> throw IllegalArgumentException("Unrecognised schema $schema")
            }
        }

        /** Object Relational Mapping support. */
        override fun supportedSchemas(): Iterable<MappedSchema> = listOf(SampleCashSchemaV1, SampleCashSchemaV2, SampleCashSchemaV3)
    }

    interface Commands : FungibleAsset.Commands {

        data class Move(override val contractHash: SecureHash? = null) : FungibleAsset.Commands.Move, Commands

        data class Issue(override val nonce: Long = newSecureRandom().nextLong()) : FungibleAsset.Commands.Issue, Commands

        data class Exit(override val amount: Amount<Issued<Currency>>) : Commands, FungibleAsset.Commands.Exit<Currency>
    }

    fun generateIssue(tx: TransactionBuilder, tokenDef: Issued<Currency>, pennies: Long, owner: AbstractParty, notary: Party)
            = generateIssue(tx, Amount(pennies, tokenDef), owner, notary)

    fun generateIssue(tx: TransactionBuilder, amount: Amount<Issued<Currency>>, owner: AbstractParty, notary: Party)
        = generateIssue(tx, TransactionState(State(amount, owner), notary), generateIssueCommand())

    override fun deriveState(txState: TransactionState<State>, amount: Amount<Issued<Currency>>, owner: AbstractParty)
            = txState.copy(data = txState.data.copy(amount = amount, owner = owner))

    override fun generateExitCommand(amount: Amount<Issued<Currency>>) = Commands.Exit(amount)
    override fun generateIssueCommand() = Commands.Issue()
    override fun generateMoveCommand() = Commands.Move()

    override fun verify(tx: LedgerTransaction) {

        val groups = tx.groupStates { it: State -> it.amount.token }

        for ((inputs, outputs, key) in groups) {
            // Either inputs or outputs could be empty.
            val issuer = key.issuer
            val currency = key.product

            requireThat {
                "there are no zero sized outputs" using (outputs.none { it.amount.quantity == 0L })
            }

            val issueCommand = tx.commands.select<Commands.Issue>().firstOrNull()
            if (issueCommand != null) {
                verifyIssueCommand(inputs, outputs, tx, issueCommand, currency, issuer)
            } else {
                val inputAmount = inputs.sumCashOrNull() ?: throw IllegalArgumentException("there is at least one input for this group")
                val outputAmount = outputs.sumCashOrZero(Issued(issuer, currency))

                val exitKeys: Set<PublicKey> = inputs.flatMap { it.exitKeys }.toSet()
                val exitCommand = tx.commands.select<Commands.Exit>(parties = null, signers = exitKeys).filter { it.value.amount.token == key }.singleOrNull()
                val amountExitingLedger = exitCommand?.value?.amount ?: Amount(0, Issued(issuer, currency))

                requireThat {
                    "there are no zero sized inputs" using inputs.none { it.amount.quantity == 0L }
                    "for reference ${issuer.reference} at issuer ${issuer.party} the amounts balance: ${inputAmount.quantity} - ${amountExitingLedger.quantity} != ${outputAmount.quantity}" using
                            (inputAmount == outputAmount + amountExitingLedger)
                }

                verifyMoveCommand<Commands.Move>(inputs, tx.commands)
            }
        }
    }

    private fun verifyIssueCommand(inputs: List<State>,
                                   outputs: List<State>,
                                   tx: LedgerTransaction,
                                   issueCommand: AuthenticatedObject<Commands.Issue>,
                                   currency: Currency,
                                   issuer: PartyAndReference) {
        // If we have an issue command, perform special processing: the group is allowed to have no inputs,
        // and the output states must have a deposit reference owned by the signer.
        //
        // Whilst the transaction *may* have no inputs, it can have them, and in this case the outputs must
        // sum to more than the inputs. An issuance of zero size is not allowed.
        //
        // Note that this means literally anyone with access to the network can issue cash claims of arbitrary
        // amounts! It is up to the recipient to decide if the backing party is trustworthy or not, via some
        // as-yet-unwritten identity service. See ADP-22 for discussion.

        // The grouping ensures that all outputs have the same deposit reference and currency.
        val inputAmount = inputs.sumCashOrZero(Issued(issuer, currency))
        val outputAmount = outputs.sumCash()
        val cashCommands = tx.commands.select<Commands.Issue>()
        requireThat {
            "the issue command has a nonce" using (issueCommand.value.nonce != 0L)
            // TODO: This doesn't work with the trader demo, so use the underlying key instead
            // "output states are issued by a command signer" by (issuer.party in issueCommand.signingParties)
            "output states are issued by a command signer" using (issuer.party.owningKey in issueCommand.signers)
            "output values sum to more than the inputs" using (outputAmount > inputAmount)
            "there is only a single issue command" using (cashCommands.count() == 1)
        }
    }
}

