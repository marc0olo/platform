import { Injectable } from '@angular/core';
import {
  HttpRequest,
  HttpHandler,
  HttpEvent,
  HttpInterceptor
} from '@angular/common/http';

import { Observable } from 'rxjs/Observable';
import {AuthService} from "./auth.service";

@Injectable()
export class TokenInterceptor implements HttpInterceptor {
  constructor(public auth: AuthService) {}
  intercept(request: HttpRequest<any>, next: HttpHandler): Observable<HttpEvent<any>> {
    /*console.log(request);
    const newRequest: HttpRequest<any> = request.clone({
      setHeaders: {
        Authorization: `Bearer ${this.auth.getToken()}`,
        "Access-Control-Allow-Origin": "*",
      }
    });
    console.log(request, newRequest);*/

    return next.handle(request);
  }
}
