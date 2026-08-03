FROM ubuntu@sha256:4fbb8e6a8395de5a7550b33509421a2bafbc0aab6c06ba2cef9ebffbc7092d90 AS builder


RUN apt-get update && \
    apt-get install -y nodejs npm  && \
    rm -rf /var/lib/apt/lists/*

COPY ./web /build
WORKDIR /build

RUN npm --registry=https://registry.npmmirror.com install
RUN npm run build:prod

WORKDIR /src/main/resources
RUN ls

WORKDIR /src/main/resources/static
RUN ls

FROM nginx@sha256:4a73073bd557c65b759505da037898b61f1be6cbcc3c2c3aeac22d2a470c1752

ARG TZ=Asia/Shanghai


COPY --from=builder /src/main/resources/static /opt/dist

CMD ["nginx","-g","daemon off;"]
