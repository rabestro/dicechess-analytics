export class ApiClient {
  private static baseUrl = '/api';

  static async get<T>(endpoint: string, params?: Record<string, string | number>): Promise<T> {
    const url = new URL(this.baseUrl + endpoint, window.location.origin);
    if (params) {
      Object.entries(params).forEach(([key, value]) => {
        if (value !== undefined && value !== null) {
          url.searchParams.append(key, value.toString());
        }
      });
    }

    const response = await fetch(url.toString(), {
      method: 'GET',
      headers: {
        'Accept': 'application/json',
      },
    });

    if (!response.ok) {
      throw new Error(`API GET Error: ${response.status} ${response.statusText}`);
    }

    return response.json();
  }

  static async post<T>(endpoint: string, body: any): Promise<T> {
    const url = new URL(this.baseUrl + endpoint, window.location.origin);

    const response = await fetch(url.toString(), {
      method: 'POST',
      headers: {
        'Accept': 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(body),
    });

    if (!response.ok) {
      throw new Error(`API POST Error: ${response.status} ${response.statusText}`);
    }

    return response.json();
  }
}
