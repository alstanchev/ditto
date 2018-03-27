/*
 * Copyright (c) 2017 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/index.php
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial contribution
 */
package org.eclipse.ditto.services.connectivity.messaging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import org.eclipse.ditto.model.connectivity.Connection;
import org.eclipse.ditto.model.connectivity.ConnectionStatus;
import org.eclipse.ditto.model.connectivity.MappingContext;

/**
 * The data the {@link BaseClientActor} has in its different {@link BaseClientState States}.
 */
@Immutable
public final class BaseClientData {

    private final String connectionId;
    private final Connection connection;
    private final ConnectionStatus connectionStatus;
    @Nullable private final String connectionStatusDetails;
    private final List<MappingContext> mappingContexts;

    /**
     * Constructs new instance of BaseClientData, the data of the {@link BaseClientActor}.
     *
     * @param connectionId the ID of the {@link Connection}.
     * @param connection the optional {@link Connection}.
     * @param connectionStatus the optional {@link ConnectionStatus} of the Connection.
     * @param connectionStatusDetails the optional details about the ConnectionStatus.
     * @param mappingContexts the {@link MappingContext}s to apply for the managed Connection.
     */
    BaseClientData(final String connectionId, final Connection connection,
            final ConnectionStatus connectionStatus,
            @Nullable final String connectionStatusDetails, final List<MappingContext> mappingContexts) {
        this.connectionId = connectionId;
        this.connection = connection;
        this.connectionStatus = connectionStatus;
        this.connectionStatusDetails = connectionStatusDetails;
        this.mappingContexts = Collections.unmodifiableList(new ArrayList<>(mappingContexts));
    }

    public String getConnectionId() {
        return connectionId;
    }

    public Connection getConnection() {
        return connection;
    }

    public ConnectionStatus getConnectionStatus() {
        return connectionStatus;
    }

    public Optional<String> getConnectionStatusDetails() {
        return Optional.ofNullable(connectionStatusDetails);
    }

    public List<MappingContext> getMappingContexts() {
        return mappingContexts;
    }

    public BaseClientData setConnection(final Connection connection) {
        return new BaseClientData(connectionId, connection, connectionStatus, connectionStatusDetails, mappingContexts);
    }

    public BaseClientData setConnectionStatus(final ConnectionStatus connectionStatus) {
        return new BaseClientData(connectionId, connection, connectionStatus, connectionStatusDetails, mappingContexts);
    }

    public BaseClientData setConnectionStatusDetails(final String connectionStatusDetails) {
        return new BaseClientData(connectionId, connection, connectionStatus, connectionStatusDetails, mappingContexts);
    }

    public BaseClientData setMappingContexts(final List<MappingContext> mappingContexts) {
        return new BaseClientData(connectionId, connection, connectionStatus, connectionStatusDetails, mappingContexts);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {return true;}
        if (!(o instanceof BaseClientData)) {return false;}
        final BaseClientData that = (BaseClientData) o;
        return Objects.equals(connectionId, that.connectionId) &&
                Objects.equals(connection, that.connection) &&
                connectionStatus == that.connectionStatus &&
                Objects.equals(connectionStatusDetails, that.connectionStatusDetails) &&
                Objects.equals(mappingContexts, that.mappingContexts);
    }

    @Override
    public int hashCode() {
        return Objects.hash(connectionId, connection, connectionStatus, connectionStatusDetails, mappingContexts);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " [" +
                "connectionId=" + connectionId +
                ", connection=" + connection +
                ", connectionStatus=" + connectionStatus +
                ", connectionStatusDetails=" + connectionStatusDetails +
                ", mappingContexts=" + mappingContexts +
                "]";
    }
}

