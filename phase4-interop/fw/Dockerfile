FROM alpine:3.20
RUN apk add --no-cache iptables socat
COPY start.sh /start.sh
RUN chmod +x /start.sh
CMD ["/start.sh"]
