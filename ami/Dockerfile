FROM mesos/mesos-mini:1.9.x

# Install prerequisites
RUN yum -y install git python-3.6.2 jq openjdk-8-jdk

# Install Mesos via MVM
# COPY mvm.sh /usr/local/bin/mvm
# RUN mvm

# Install Python
#RUN curl https://pyenv.run | bash
#RUN pyenv install 3.6.2 && pyenv global 3.6.2

# Install Ammonite
RUN curl -L -o /usr/local/bin/amm https://github.com/lihaoyi/Ammonite/releases/download/2.0.1/2.12-2.0.1 \
  && chmod +x /usr/local/bin/amm \
  && cp /usr/local/bin/amm /usr/local/bin/amm-2.12
