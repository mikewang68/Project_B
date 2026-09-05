FROM nginx:1.24-alpine
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY index.html styles.css app.js /usr/share/nginx/html/
EXPOSE 80
