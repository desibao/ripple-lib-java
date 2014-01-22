package com.ripple.client.transactions;

import com.ripple.client.Client;
import com.ripple.client.enums.Command;
import com.ripple.client.pubsub.Publisher;
import com.ripple.client.requests.Request;
import com.ripple.client.responses.Response;
import com.ripple.client.subscriptions.AccountRoot;
import com.ripple.client.subscriptions.ServerInfo;
import com.ripple.core.enums.TransactionEngineResult;
import com.ripple.core.enums.TransactionType;
import com.ripple.core.coretypes.AccountID;
import com.ripple.core.coretypes.Amount;
import com.ripple.core.coretypes.hash.Hash256;
import com.ripple.core.coretypes.uint.UInt32;
import com.ripple.crypto.ecdsa.IKeyPair;
import com.ripple.encodings.common.B16;

import java.util.*;

public class TransactionManager extends Publisher<TransactionManager.events> {
    Client client;
    AccountRoot accountRoot;
    AccountID accountID;
    IKeyPair keyPair;
    public long sequence = -1;

    Set<Long> seenValidatedSequenes = new TreeSet<Long>();

    public void queue(ManagedTxn tx) {
        queue(tx, null);
    }

    public ArrayList<ManagedTxn> getQueue() {
        return queued;
    }

    public ArrayList<ManagedTxn> sequenceSortedQueue() {
        ArrayList<ManagedTxn> queued = new ArrayList<ManagedTxn>(getQueue());
        Collections.sort(queued, new Comparator<ManagedTxn>() {
            @Override
            public int compare(ManagedTxn lhs, ManagedTxn rhs) {
                return lhs.get(UInt32.Sequence).subtract(rhs.get(UInt32.Sequence)).intValue();
            }
        });
        return queued;
    }

    public static abstract class events<T> extends Publisher.Callback<T> {
    }

    // This event is emitted with the Sequence of the AccountRoot
    public static abstract class OnValidatedSequence extends events<UInt32> {}

    private ArrayList<ManagedTxn> queued = new ArrayList<ManagedTxn>();

    public int awaiting() {
        return getQueue().size();
    }

    public TransactionManager(Client client, final AccountRoot accountRoot, AccountID accountID, IKeyPair keyPair) {
        this.client = client;
        this.accountRoot = accountRoot;
        this.accountID = accountID;
        this.keyPair = keyPair;

        this.client.on(Client.OnLedgerClosed.class, new Client.OnLedgerClosed() {
            @Override
            public void called(ServerInfo serverInfo) {
                if (!canSubmit() || getQueue().isEmpty()) {
                    return;
                }
                ArrayList<ManagedTxn> sorted = sequenceSortedQueue();

                ManagedTxn first = sorted.get(0);
                Submission previous = first.lastSubmission();
                long ledgersClosed = serverInfo.ledger_index - previous.ledgerSequence;

                if (ledgersClosed > 5) {
                    resubmitWithSameSequence(first);
                }
            }
        });
    }

    public void queue(final ManagedTxn transaction, final UInt32 sequence) {
        getQueue().add(transaction);

        if (canSubmit()) {
            makeSubmitRequest(transaction, sequence);
        } else {
            // We wait for basically any old event n see if we are primed after each, angular styles
            client.on(Client.OnStateChange.class, new Client.OnStateChange() {
                @Override
                public void called(Client client) {
                    if (canSubmit()) {
                        client.removeListener(Client.OnStateChange.class, this);
                        makeSubmitRequest(transaction, sequence);
                    }
                }
            });
        }
    }

    private boolean canSubmit() {
        return client.serverInfo.primed() && client.serverInfo.load_factor < 512 && accountRoot.primed();
    }

    private Request makeSubmitRequest(final ManagedTxn transaction, UInt32 sequence) {
        Amount fee = client.serverInfo.transactionFee(transaction);
        // TODO: inside prepare, check if Fee and Sequence are the same and don't resign ;)
        transaction.prepare(keyPair, fee, sequence == null ? getSubmissionSequence() : sequence);
        final Request req = client.newRequest(Command.submit);
        req.json("tx_blob", B16.toString(transaction.tx_blob));


        // DUH, this only works if you are submitting raw tx_json, not a blob
        /*
        if (transaction.transactionType() == TransactionType.Payment &&
            !transaction.get(Amount.Amount).isNative() &&
            !transaction.has(PathSet.Paths)) {
            req.json("build_path", true);
            // TODO: Add a SendMax
        }
        */

        req.once(Request.OnSuccess.class, new Request.OnSuccess() {
            @Override
            public void called(Response response) {
                handleSubmitSuccess(transaction, response);
            }
        });

        req.once(Request.OnError.class, new Request.OnError() {
            @Override
            public void called(Response response) {
                handleSubmitError(transaction, response);
            }
        });

        transaction.trackSubmitRequest(req, client.serverInfo);
        req.request();
        return req;
    }

    /*
    * The $10,000 question is when does sequence get decremented?
    * Never ... just patch holes ...
    **/
    private UInt32 getSubmissionSequence() {
        long server = accountRoot.Sequence.longValue();
        if (sequence == -1 || server > sequence) {
            sequence = server;
        }
        return new UInt32(sequence++);
    }

    public void handleSubmitError(ManagedTxn transaction, Response res) {
//        resubmitFirstTransactionWithTakenSequence(transaction.sequence());
        if (transaction.finalizedOrHandlerForPriorSubmission(res)) {
            return;
        }
        switch (res.rpcerr) {
            case noNetwork:
                resubmitWithSameSequence(transaction);
                break;
            default:
                // TODO, what other cases should this retry?
                finalizeTxnAndRemoveFromQueue(transaction);
                transaction.publisher().emit(ManagedTxn.OnSubmitError.class, res);
                break;
        }
    }

    public void handleSubmitSuccess(final ManagedTxn transaction, final Response res) {
        if (transaction.finalizedOrHandlerForPriorSubmission(res)) {
            return;
        }
        TransactionEngineResult tr = res.engineResult();
        final UInt32 submitSequence = res.getSubmitSequence();
        switch (tr) {
            case tesSUCCESS:
                transaction.publisher().emit(ManagedTxn.OnSubmitSuccess.class, res);
                return;

            case tefPAST_SEQ:
                resubmitWithNewSequence(transaction);
                break;
            case terPRE_SEQ:
                on(OnValidatedSequence.class, new OnValidatedSequence() {
                    @Override
                    public void called(UInt32 sequence) {
                        if (transaction.finalizedOrHandlerForPriorSubmission(res)) {
                            removeListener(OnValidatedSequence.class, this);
                        }
                        if (sequence.equals(submitSequence)) {
                            // resubmit:
                            resubmit(transaction, submitSequence);
                        }
                    }
                });
                break;
            case telINSUF_FEE_P:
                resubmit(transaction, submitSequence);
                break;
            case tefALREADY:
                // We only get this if we are submitting with the correct transactionID
                // Do nothing, the transaction has already been submitted
                break;
            default:
                // In which cases do we patch ?
                switch (tr.resultClass()) {
                    case tecCLAIMED:
                        // Sequence was consumed, do nothing
                        finalizeTxnAndRemoveFromQueue(transaction);
                        transaction.publisher().emit(ManagedTxn.OnSubmitError.class, res);
                        break;
                    // What about this craziness /??
                    case temMALFORMED:
                    case tefFAILURE:
                    case telLOCAL_ERROR:
                    case terRETRY:
                        finalizeTxnAndRemoveFromQueue(transaction);
                        if (getQueue().isEmpty()) {
                            sequence--;
                        } else {
                            // Plug a Sequence gap and pre-emptively resubmit some
                            // rather than waiting for `OnValidatedSequence` which will take
                            // quite some ledgers
                            noopTransaction(submitSequence);
                            resubmitGreaterThan(submitSequence);
                        }
                        transaction.publisher().emit(ManagedTxn.OnSubmitError.class, res);
                        // look for
//                        queued.add(transaction);
                        // This is kind of nasty ..
                        break;

                }
                // TODO: Disposition not final ??!?!? ... ????
//                if (tr.resultClass() == TransactionEngineResult.Class.telLOCAL_ERROR) {
//                    queued.add(transaction);
//                }
                break;
        }
    }

    private void resubmitGreaterThan(UInt32 submitSequence) {
        for (ManagedTxn txn : getQueue()) {
            if (txn.sequence().compareTo(submitSequence) == 1) {
                resubmitWithSameSequence(txn);
            }
        }
    }

    private void noopTransaction(UInt32 sequence) {
        ManagedTxn plug = transaction(TransactionType.AccountSet);
        queue(plug, sequence);
    }

    public void finalizeTxnAndRemoveFromQueue(ManagedTxn transaction) {
        transaction.setFinalized();
        getQueue().remove(transaction.publisher());
    }

    private void resubmitFirstTransactionWithTakenSequence(UInt32 sequence) {
        for (ManagedTxn txn : getQueue()) {
            if (txn.sequence().compareTo(sequence) == 0) {
                resubmitWithNewSequence(txn);
                break;
            }
        }
    }

    private void resubmitWithNewSequence(final ManagedTxn txn) {
        // TODO: ONLY ONLY ONLY if we've actually seen the Sequence
//        implies that we need to track Sequences
//                final UInt32 transactionSequence = transaction.sequence();
//                if (seenValidatedSequenes.contains(transactionSequence.longValue())) {
//                } else {
//                    // TODO: why the hell haven't we seen this Sequence?
//                    on(OnValidatedSequence.class, new OnValidatedSequence() {
//                        @Override
//                        public void called(UInt32 sequence) {
//                            if (sequence.compareTo(transactionSequence) > 0) {
//                                if (!transaction.isFinalized()) {
//                                    resubmitWithNewSequence(transaction);
//                                    removeListener(OnValidatedSequence.class, this);
//                                }
//                            }
//                        }
//                    });
//
//
//
        if (seenValidatedSequenes.contains(txn.sequence().longValue())) {
            resubmit(txn, getSubmissionSequence());
        } else {
            client.onceOnFirstLedgerClosedGreaterThan(client.serverInfo.ledger_index + 1, new Runnable() {
                @Override
                public void run() {

                }
            });

            on(OnValidatedSequence.class, new OnValidatedSequence() {
                @Override
                public void called(UInt32 uInt32) {
                    if (seenValidatedSequenes.contains(txn.sequence().longValue())) {
                        resubmit(txn, getSubmissionSequence());
                    }
                }
            });
        }
    }

//    private void resubmitAnyTransactionWithLesserSequence(UInt32 sequence) {
//        for (ManagedTxn txn : queued) {
//            if (txn.get(UInt32.Sequence).compareTo(sequence) <= 0) {
//                resubmit(txn, getSubmissionSequence());
//                break;
//            }
//        }
//    }

    private void resubmit(ManagedTxn txn, UInt32 sequence) {
        makeSubmitRequest(txn, sequence);
    }

    private void resubmitWithSameSequence(ManagedTxn txn) {
        UInt32 previouslySubmitted = txn.sequence();
        if (previouslySubmitted == null) {
            throw new IllegalStateException("We don't want to pass null " +
                    "further else it would increment");
        }
        makeSubmitRequest(txn, previouslySubmitted);
    }

    public ManagedTxn payment() {
        return transaction(TransactionType.Payment);
    }

    private ManagedTxn transaction(TransactionType tt) {
        ManagedTxn tx = new ManagedTxn(tt);
        tx.put(AccountID.Account, accountID);
        return tx;
    }

    public void onTransactionResultMessage(TransactionResult tm) {
        if (!tm.validated) {
            return;
        }
        UInt32 txnSequence = tm.transaction.get(UInt32.Sequence);
        seenValidatedSequenes.add(txnSequence.longValue());

        ManagedTxn tx = submittedTransaction(tm.hash);
        if (tx != null) {
            finalizeTxnAndRemoveFromQueue(tx);
            tx.publisher().emit(ManagedTxn.OnTransactionValidated.class, tm);
        } else {
            resubmitFirstTransactionWithTakenSequence(txnSequence);
            emit(OnValidatedSequence.class, txnSequence.add(new UInt32(1)));
        }
    }

    private ManagedTxn submittedTransaction(Hash256 hash) {
        Iterator<ManagedTxn> iterator = getQueue().iterator();

        while (iterator.hasNext()) {
            ManagedTxn transaction = iterator.next();
            if (transaction.wasSubmittedWith(hash)) {
                iterator.remove();
                return transaction;
            }
        }
        return null;
    }
}
