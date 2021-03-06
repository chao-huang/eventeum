package net.consensys.eventeum.chain.service;

import net.consensys.eventeum.chain.service.domain.TransactionReceipt;
import net.consensys.eventeum.chain.util.Web3jUtil;
import net.consensys.eventeum.chain.service.domain.wrapper.Web3jTransactionReceipt;
import net.consensys.eventeum.chain.service.factory.ContractEventDetailsFactory;
import net.consensys.eventeum.dto.block.BlockDetails;
import net.consensys.eventeum.dto.event.filter.ContractEventFilter;
import net.consensys.eventeum.dto.event.filter.ContractEventSpecification;
import net.consensys.eventeum.service.AsyncTaskService;
import net.consensys.eventeum.chain.block.BlockListener;
import net.consensys.eventeum.chain.contract.ContractEventListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.DefaultBlockParameterNumber;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.*;
import rx.Observable;
import rx.Subscription;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A BlockchainService implementating utilising the Web3j library.
 *
 * @author Craig Williams <craig.williams@consensys.net>
 */
@Service
public class Web3jService implements BlockchainService {

    private Web3j web3j;
    private ContractEventDetailsFactory eventDetailsFactory;
    private EventBlockManagementService blockManagement;
    private AsyncTaskService asyncTaskService;

    private Collection<BlockListener> blockListeners;
    private Subscription blockSubscription;

    @Autowired
    public Web3jService(Web3j web3j,
                        ContractEventDetailsFactory eventDetailsFactory,
                        EventBlockManagementService blockManagement,
                        AsyncTaskService asyncTaskService) {
        this.web3j = web3j;
        this.eventDetailsFactory = eventDetailsFactory;
        this.blockManagement = blockManagement;
        this.asyncTaskService = asyncTaskService;

        this.blockListeners = new ConcurrentLinkedQueue<>();

        connect();
    }

    /**
     * {inheritDoc}
     */
    @Override
    public void addBlockListener(BlockListener blockListener) {
        blockListeners.add(blockListener);
    }

    /**
     * {inheritDoc}
     */
    @Override
    public void removeBlockListener(BlockListener blockListener) {
        blockListeners.remove(blockListener);
    }

    /**
     * {inheritDoc}
     */
    @Override
    public Subscription registerEventListener(
            ContractEventFilter eventFilter, ContractEventListener eventListener) {
        final ContractEventSpecification eventSpec = eventFilter.getEventSpecification();

        EthFilter ethFilter = new EthFilter(
                new DefaultBlockParameterNumber(getStartBlockForEventSpec(eventSpec)),
                DefaultBlockParameterName.LATEST, eventFilter.getContractAddress());

        if (eventFilter.getEventSpecification() != null) {
            ethFilter = ethFilter.addSingleTopic(Web3jUtil.getSignature(eventSpec));
        }

        final Observable<Log> observable = web3j.ethLogObservable(ethFilter);

        final Subscription sub = observable.subscribe(log -> {
            eventListener.onEvent(
                    eventDetailsFactory.createEventDetails(eventFilter, log));
        });

        return sub;
    }

    /**
     * {inheritDoc}
     */
    @Override
    public void reconnect() {
        blockSubscription.unsubscribe();
        connect();
    }

    /**
     * {inheritDoc}
     */
    @Override
    public String getClientVersion() {
        try {
            final Web3ClientVersion web3ClientVersion = web3j.web3ClientVersion().send();
            return web3ClientVersion.getWeb3ClientVersion();
        } catch (IOException e) {
            throw new BlockchainException("Error when obtaining client version", e);
        }
    }

    /**
     * {inheritDoc}
     */
    @Override
    public TransactionReceipt getTransactionReceipt(String txId) {
        try {
            final EthGetTransactionReceipt response = web3j.ethGetTransactionReceipt(txId).send();

            return response
                    .getTransactionReceipt()
                    .map(receipt -> new Web3jTransactionReceipt(receipt))
                    .orElse(null);
        } catch (IOException e) {
            throw new BlockchainException("Unable to connect to the ethereum client", e);
        }
    }

    /**
     * {inheritDoc}
     */
    @Override
    public BigInteger getCurrentBlockNumber() {
        try {
            final EthBlockNumber ethBlockNumber = web3j.ethBlockNumber().send();

            return ethBlockNumber.getBlockNumber();
        } catch (IOException e) {
            throw new BlockchainException("Error when obtaining the current block number", e);
        }
    }

    @PreDestroy
    private void unregisterBlockSubscription() {
        blockSubscription.unsubscribe();
    }

    private void connect() {
        blockSubscription = registerBlockOberservable();
    }

    private BigInteger getStartBlockForEventSpec(ContractEventSpecification spec) {
        return blockManagement.getLatestBlockForEvent(spec);
    }

    private Subscription registerBlockOberservable() {
        return web3j.blockObservable(false).subscribe(block -> {
            blockListeners.forEach(listener ->
                    asyncTaskService.execute(() -> listener.onBlock(blockToBlockDetails(block))));
        });
    }

    private BlockDetails blockToBlockDetails(EthBlock ethBlock) {
        final EthBlock.Block block = ethBlock.getBlock();
        final BlockDetails blockDetails = new BlockDetails();

        blockDetails.setNumber(block.getNumber());
        blockDetails.setHash(block.getHash());

        return blockDetails;
    }
}
