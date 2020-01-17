FROM nginx
LABEL maintainer wangzan18
RUN rm -rf /usr/share/nginx/html/*
ADD htdocs/* /usr/share/nginx/html/