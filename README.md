# Quorum-based Total Order Broadcast

## Overview

This project implements a protocol for coordinating a group of replicas in a distributed system using a quorum-based approach. The system ensures fault tolerance and sequential consistency by handling update requests from external clients and applying updates in the same order across all replicas. Key features include coordinator election, crash detection, and consistent client interactions.

## Key Features

- **Quorum-Based Update Protocol**: Guarantees that updates are applied in the same order across all replicas.
- **Coordinator Election**: Handles the election of a new coordinator if the current one crashes, using a simple ring-based protocol.
- **Crash Detection**: Implements a timeout-based mechanism to detect and handle crashes.
- **Sequential Consistency**: Ensures that clients always see updates in the same order, interacting with a single replica.
- **Logging**: Records key protocol steps and events for debugging and verification.

## Components

- **Akka Framework**: Implemented using Akka actors to represent replicas and handle communication.
- **Client Interactions**: Clients can issue read and write requests to any replica.
- **Coordinator Role**: Manages update requests through a two-phase broadcast protocol.
- **Update Identification**: Uses `<epoch, sequence number>` pairs to ensure uniqueness and order of updates.
- **Logging**: Essential protocol steps and events are recorded in log files.

## Getting Started

### Prerequisites

- Java 8 or higher
- Gradle 5.0 or higher
- Akka dependencies (included in Gradle build scripts)

### Installation

1. **Clone the Repository**

   ```bash
   git clone https://github.com/YourUsername/YourRepository.git
   cd YourRepository
