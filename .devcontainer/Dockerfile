# Base image
FROM mcr.microsoft.com/devcontainers/java:21

# Install Maven
RUN apt-get update && \
    apt-get install -y maven && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Install Quarkus CLI
RUN curl -Ls https://sh.jbang.dev | bash -s - trust add https://repo1.maven.org/maven2/io/quarkus/quarkus-cli/
RUN curl -Ls https://sh.jbang.dev | bash -s - app install --fresh --force quarkus@quarkusio

# Set environment variables for Quarkus
ENV QUARKUS_CLI_INSTALL_DIR=/home/vscode/.jbang/bin
ENV PATH=$QUARKUS_CLI_INSTALL_DIR:$PATH

# Set the user to "vscode"
USER vscode
